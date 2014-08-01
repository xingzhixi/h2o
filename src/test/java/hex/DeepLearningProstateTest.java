package hex;

import hex.deeplearning.DeepLearning;
import hex.deeplearning.DeepLearningModel;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import water.*;
import water.api.AUC;
import water.api.AUCData;
import water.exec.Env;
import water.exec.Exec2;
import water.fvec.Frame;
import water.fvec.NFSFileVec;
import water.fvec.ParseDataset2;
import water.util.Log;

import java.util.Arrays;
import java.util.Random;

public class DeepLearningProstateTest extends TestUtil {
  @BeforeClass public static void stall() {
    stall_till_cloudsize(JUnitRunnerDebug.NODES);
  }

  public void runFraction(float fraction) {
    long seed = 0xDECAF;
    Random rng = new Random(seed);
    String[] datasets = new String[2];
    int[][] responses = new int[datasets.length][];
    datasets[0] = "smalldata/./logreg/prostate.csv"; responses[0] = new int[]{1,2,8}; //CAPSULE (binomial), AGE (regression), GLEASON (multi-class)
    datasets[1] = "smalldata/iris/iris.csv";  responses[1] = new int[]{4}; //Iris-type (multi-class)

    int testcount = 0;
    int count = 0;
    for (int i =0;i<datasets.length;++i) {
      String dataset = datasets[i];
      Key file = NFSFileVec.make(find_test_file(dataset));
      Frame frame = ParseDataset2.parse(Key.make(), new Key[]{file});
      Key vfile = NFSFileVec.make(find_test_file(dataset));
      Frame vframe = ParseDataset2.parse(Key.make(), new Key[]{vfile});

      for (boolean replicate : new boolean[]{
              true,
              false,
      }) {
        for (boolean load_balance : new boolean[]{
                true,
                false,
        }) {
          for (boolean shuffle : new boolean[]{
                  true,
                  false,
          }) {
            for (boolean balance_classes : new boolean[]{
                    true,
                    false,
            }) {
              for (int resp : responses[i]) {
                for (DeepLearning.ClassSamplingMethod csm : new DeepLearning.ClassSamplingMethod[] {
                        DeepLearning.ClassSamplingMethod.Stratified,
                        DeepLearning.ClassSamplingMethod.Uniform
                }) {
                  for (int scoretraining : new int[]{
                          200,
                          0,
                  }) {
                    for (int scorevalidation : new int[]{
                            200,
                            0,
                    }) {
                      for (int vf : new int[]{
                              0,  //no validation
                              1,  //same as source
                              -1, //different validation frame
                      }) {
                        for (int train_samples_per_iteration : new int[] {
                                -1, //N epochs per iteration
                                0, //1 epoch per iteration
                                rng.nextInt(100), // <1 epoch per iteration
                                500, //>1 epoch per iteration
                        })
                        {
                          for (boolean override_with_best_model : new boolean[]{false, true}) {
                            count++;
                            if (fraction < rng.nextFloat()) continue;
                            final double epochs = 7 + rng.nextDouble() + rng.nextInt(4);
                            final int[] hidden = new int[]{1 + rng.nextInt(4), 1 + rng.nextInt(6)};

                            Log.info("**************************)");
                            Log.info("Starting test #" + count);
                            Log.info("**************************)");
                            Frame valid = null; //no validation
                            if (vf == 1) valid = frame; //use the same frame for validation
                            else if (vf == -1) valid = vframe; //different validation frame (here: from the same file)

                            // build the model, with all kinds of shuffling/rebalancing/sampling
                            Key dest_tmp = Key.make();
                            {
                              Log.info("Using seed: " + seed);
                              DeepLearning p = new DeepLearning();
                              p.checkpoint = null;
                              p.destination_key = dest_tmp;

                              p.source = frame;
                              p.response = frame.vecs()[resp];
                              p.validation = valid;

                              p.hidden = hidden;
                              if (i == 0 && resp == 2) p.classification = false;
//                                p.best_model_key = best_model_key;
                              p.override_with_best_model = override_with_best_model;
                              p.epochs = epochs;
                              p.seed = seed;
                              p.train_samples_per_iteration = train_samples_per_iteration;
                              p.force_load_balance = load_balance;
                              p.replicate_training_data = replicate;
                              p.shuffle_training_data = shuffle;
                              p.score_training_samples = scoretraining;
                              p.score_validation_samples = scorevalidation;
                              p.classification_stop = -1;
                              p.regression_stop = -1;
                              p.balance_classes = balance_classes;
                              p.quiet_mode = true;
//                              p.quiet_mode = false;
                              p.score_validation_sampling = csm;
                              p.invoke();
                            }

                            // Do some more training via checkpoint restart
                            Key dest = Key.make();
                            {
                              DeepLearning p = new DeepLearning();
                              final DeepLearningModel tmp_model = UKV.get(dest_tmp); //this actually *requires* frame to also still be in UKV (because of DataInfo...)
                              Assert.assertTrue(tmp_model.get_params().state == Job.JobState.DONE); //HEX-1817
                              Assert.assertTrue(tmp_model.model_info().get_processed_total() >= frame.numRows() * epochs);
                              assert (tmp_model != null);
                              p.checkpoint = dest_tmp;
                              p.destination_key = dest;

                              p.source = frame;
                              p.validation = valid;
                              p.response = frame.vecs()[resp];

                              if (i == 0 && resp == 2) p.classification = false;
                              p.override_with_best_model = override_with_best_model;
                              p.epochs = epochs;
                              p.seed = seed;
                              p.train_samples_per_iteration = train_samples_per_iteration;
                              p.invoke();
                            }

                            // score and check result (on full data)
                            final DeepLearningModel mymodel = UKV.get(dest); //this actually *requires* frame to also still be in UKV (because of DataInfo...)
                            Assert.assertTrue(mymodel.get_params().state == Job.JobState.DONE); //HEX-1817
                            // test HTML
                            {
                              StringBuilder sb = new StringBuilder();
                              mymodel.generateHTML("test", sb);
                            }

                            // score and check result of the best_model
                            if (mymodel.actual_best_model_key != null) {
                              final DeepLearningModel best_model = UKV.get(mymodel.actual_best_model_key);
                              Assert.assertTrue(best_model.get_params().state == Job.JobState.DONE); //HEX-1817
                              // test HTML
                              {
                                StringBuilder sb = new StringBuilder();
                                best_model.generateHTML("test", sb);
                              }
                              if (override_with_best_model) {
                                Assert.assertEquals(best_model.error(), mymodel.error(), 0);
                              }
                            }

                            if (valid == null) valid = frame;
                            double threshold = 0;
                            if (mymodel.isClassifier()) {
                              Frame pred = mymodel.score(valid);
                              StringBuilder sb = new StringBuilder();

                              AUC auc = new AUC();
                              double error = 0;
                              // binary
                              if (mymodel.nclasses() == 2) {
                                auc.actual = valid;
                                assert (resp == 1);
                                auc.vactual = valid.vecs()[resp];
                                auc.predict = pred;
                                auc.vpredict = pred.vecs()[2];
                                auc.invoke();
                                auc.toASCII(sb);
                                AUCData aucd = auc.data();
                                threshold = aucd.threshold();
                                error = aucd.err();
                                Log.info(sb);

                                // test AUC computation in more detail
                                Assert.assertTrue(aucd.AUC > 0.75); //min val = 0.81 for long test
                                Assert.assertTrue(aucd.AUC < 0.9);  //max val = 0.85 for long test
                                Assert.assertTrue(aucd.threshold() > 0.1);  //min val = 0.17 for long test
                                Assert.assertTrue(aucd.threshold() < 0.6);  //max val = 0.53 for long test

                                // check that auc.cm() is the right CM
                                Assert.assertEquals(new ConfusionMatrix(aucd.cm()).err(), error, 1e-15);

                                // check that calcError() is consistent as well (for CM=null, AUC!=null)
                                Assert.assertEquals(mymodel.calcError(valid, auc.vactual, pred, pred, "training", false, 0, null, auc, null), error, 1e-15);
                              }

                              // Compute CM
                              double CMerrorOrig;
                              {
                                sb = new StringBuilder();
                                water.api.ConfusionMatrix CM = new water.api.ConfusionMatrix();
                                CM.actual = valid;
                                CM.vactual = valid.vecs()[resp];
                                CM.predict = pred;
                                CM.vpredict = pred.vecs()[0];
                                CM.invoke();
                                sb.append("\n");
                                sb.append("Threshold: " + "default\n");
                                CM.toASCII(sb);
                                Log.info(sb);
                                CMerrorOrig = new ConfusionMatrix(CM.cm).err();
                              }

                              // confirm that orig CM was made with threshold 0.5
                              // put pred2 into UKV, and allow access
                              Frame pred2 = new Frame(Key.make("pred2"), pred.names(), pred.vecs());
                              pred2.delete_and_lock(null);
                              pred2.unlock(null);

                              if (mymodel.nclasses() == 2) {
                                // make labels with 0.5 threshold for binary classifier
                                Env ev = Exec2.exec("pred2[,1]=pred2[,3]>=" + 0.5);
                                pred2 = ev.popAry();
                                ev.subRef(pred2, "pred2");
                                ev.remove_and_unlock();

                                water.api.ConfusionMatrix CM = new water.api.ConfusionMatrix();
                                CM.actual = valid;
                                CM.vactual = valid.vecs()[1];
                                CM.predict = pred2;
                                CM.vpredict = pred2.vecs()[0];
                                CM.invoke();
                                sb = new StringBuilder();
                                sb.append("\n");
                                sb.append("Threshold: " + 0.5 + "\n");
                                CM.toASCII(sb);
                                Log.info(sb);
                                double threshErr = new ConfusionMatrix(CM.cm).err();
                                Assert.assertEquals(threshErr, CMerrorOrig, 1e-15);

                                // make labels with AUC-given threshold for best F1
                                ev = Exec2.exec("pred2[,1]=pred2[,3]>=" + threshold);
                                pred2 = ev.popAry();
                                ev.subRef(pred2, "pred2");
                                ev.remove_and_unlock();

                                CM = new water.api.ConfusionMatrix();
                                CM.actual = valid;
                                CM.vactual = valid.vecs()[1];
                                CM.predict = pred2;
                                CM.vpredict = pred2.vecs()[0];
                                CM.invoke();
                                sb = new StringBuilder();
                                sb.append("\n");
                                sb.append("Threshold: " + threshold + "\n");
                                CM.toASCII(sb);
                                Log.info(sb);
                                double threshErr2 = new ConfusionMatrix(CM.cm).err();
                                Assert.assertEquals(threshErr2, error, 1e-15);
                              }
                              pred2.delete();
                              pred.delete();
                            } //classifier

                            mymodel.delete();
                            UKV.remove(dest);
                            UKV.remove(dest_tmp);
                            Log.info("Parameters combination " + count + ": PASS");
                            testcount++;
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
      frame.delete();
      vframe.delete();
    }
    Log.info("\n\n=============================================");
    Log.info("Tested " + testcount + " out of " + count + " parameter combinations.");
    Log.info("=============================================");
  }

  public static class Long extends DeepLearningProstateTest {
    @Test public void run() throws Exception { runFraction(0.1f); }
  }

  public static class Short extends DeepLearningProstateTest {
    @Test public void run() throws Exception { runFraction(0.003f); }
  }
}
