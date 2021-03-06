package com.johnsnowlabs.nlp.annotators.classifier.dl

import com.johnsnowlabs.ml.tensorflow.{ClassifierDatasetEncoder, ClassifierDatasetEncoderParams, TensorflowClassifier, TensorflowWrapper}
import com.johnsnowlabs.nlp.{AnnotatorApproach, AnnotatorType, ParamsAndFeaturesWritable}
import com.johnsnowlabs.nlp.AnnotatorType.{CATEGORY, SENTENCE_EMBEDDINGS}
import com.johnsnowlabs.nlp.annotators.ner.Verbose
import com.johnsnowlabs.storage.HasStorageRef
import org.apache.spark.ml.PipelineModel
import org.apache.spark.ml.param.{BooleanParam, FloatParam, IntArrayParam, IntParam, Param}
import org.apache.spark.ml.util.{DefaultParamsReadable, Identifiable}
import org.apache.spark.sql.types.{DoubleType, FloatType, IntegerType, StringType}
import org.apache.spark.sql.{Dataset, SparkSession}

import scala.util.Random

class ClassifierDLApproach(override val uid: String)
  extends AnnotatorApproach[ClassifierDLModel]
    with ParamsAndFeaturesWritable {

  def this() = this(Identifiable.randomUID("ClassifierDL"))

  override val description = "Trains TensorFlow model for multi-class text classification"
  override val inputAnnotatorTypes: Array[AnnotatorType] = Array(SENTENCE_EMBEDDINGS)
  override val outputAnnotatorType: String = CATEGORY

  val randomSeed = new IntParam(this, "randomSeed", "Random seed")

  val labelColumn = new Param[String](this, "labelColumn", "Column with label per each document")
  val lr = new FloatParam(this, "lr", "Learning Rate")
  val batchSize = new IntParam(this, "batchSize", "Batch size")
  val dropout = new FloatParam(this, "dropout", "Dropout coefficient")
  val maxEpochs = new IntParam(this, "maxEpochs", "Maximum number of epochs to train")
  val enableOutputLogs = new BooleanParam(this, "enableOutputLogs", "Whether to output to annotators log folder")
  val validationSplit = new FloatParam(this, "validationSplit", "Choose the proportion of training dataset to be validated against the model on each Epoch. The value should be between 0.0 and 1.0 and by default it is 0.0 and off.")
  val verbose = new IntParam(this, "verbose", "Level of verbosity during training")
  val configProtoBytes = new IntArrayParam(
    this,
    "configProtoBytes",
    "ConfigProto from tensorflow, serialized into byte array. Get with config_proto.SerializeToString()"
  )

  def setLabelColumn(column: String): ClassifierDLApproach.this.type = set(labelColumn, column)
  def setLr(lr: Float): ClassifierDLApproach.this.type = set(this.lr, lr)
  def setBatchSize(batch: Int): ClassifierDLApproach.this.type = set(this.batchSize, batch)
  def setDropout(dropout: Float): ClassifierDLApproach.this.type = set(this.dropout, dropout)
  def setMaxEpochs(epochs: Int): ClassifierDLApproach.this.type = set(maxEpochs, epochs)
  def setConfigProtoBytes(bytes: Array[Int]): ClassifierDLApproach.this.type = set(this.configProtoBytes, bytes)
  def setEnableOutputLogs(enableOutputLogs: Boolean): ClassifierDLApproach.this.type = set(this.enableOutputLogs, enableOutputLogs)
  def setValidationSplit(validationSplit: Float):ClassifierDLApproach.this.type = set(this.validationSplit, validationSplit)
  def setVerbose(verbose: Int): ClassifierDLApproach.this.type = set(this.verbose, verbose)
  def setVerbose(verbose: Verbose.Level): ClassifierDLApproach.this.type = set(this.verbose, verbose.id)

  def getLabelColumn: String = $(this.labelColumn)
  def getLr: Float = $(this.lr)
  def getBatchSize: Int = $(this.batchSize)
  def getDropout: Float = $(this.dropout)
  def getEnableOutputLogs: Boolean = $(enableOutputLogs)
  def getValidationSplit: Float = $(this.validationSplit)
  def getMaxEpochs: Int = $(maxEpochs)
  def getConfigProtoBytes: Option[Array[Byte]] = get(this.configProtoBytes).map(_.map(_.toByte))

  setDefault(
    maxEpochs -> 30,
    lr -> 5e-3f,
    dropout -> 0.5f,
    batchSize -> 64,
    enableOutputLogs -> false,
    verbose -> Verbose.Silent.id,
    validationSplit -> 0.0f
  )

  override def beforeTraining(spark: SparkSession): Unit = {}

  override def train(dataset: Dataset[_], recursivePipeline: Option[PipelineModel]): ClassifierDLModel = {

    val labelColType = dataset.schema($(labelColumn)).dataType
    require(
      labelColType != StringType | labelColType != IntegerType | labelColType != DoubleType | labelColType != FloatType,
      s"The label column $labelColumn type is $labelColType and it's not compatible. " +
        s"Compatible types are StringType, IntegerType, DoubleType, or FloatType. "
    )

    val embeddingsRef = HasStorageRef.getStorageRefFromInput(dataset, $(inputCols), AnnotatorType.SENTENCE_EMBEDDINGS)

    val embeddingsField: String = ".embeddings"
    val inputColumns = (getInputCols(0) + embeddingsField)
    val train = dataset.select(dataset.col($(labelColumn)).cast("string"), dataset.col(inputColumns))
    val labels = train.select($(labelColumn)).distinct.collect.map(x => x(0).toString)

    require(
      labels.length < 50,
      s"The total unique number of classes must be less than 50. Currently is ${labels.length}"
    )

    val tf = loadSavedModel()

    val settings = ClassifierDatasetEncoderParams(
      tags = labels
    )

    val encoder = new ClassifierDatasetEncoder(
      settings
    )

    val trainDataset = encoder.collectTrainingInstances(train, getLabelColumn)
    val inputEmbeddings = encoder.extractSentenceEmbeddings(trainDataset)
    val inputLabels = encoder.extractLabels(trainDataset)

    val classifier = try {
      val model = new TensorflowClassifier(
        tensorflow = tf,
        encoder,
        Verbose($(verbose))
      )
      if (isDefined(randomSeed)) {
        Random.setSeed($(randomSeed))
      }

      model.train(
        inputEmbeddings,
        inputLabels,
        lr = $(lr),
        batchSize = $(batchSize),
        dropout = $(dropout),
        endEpoch = $(maxEpochs),
        configProtoBytes = getConfigProtoBytes,
        validationSplit = $(validationSplit),
        enableOutputLogs=$(enableOutputLogs),
        uuid = this.uid
      )
      model
    } catch {
      case e: Exception =>
        throw e
    }

    val model = new ClassifierDLModel()
      .setDatasetParams(classifier.encoder.params)
      .setModelIfNotSet(dataset.sparkSession, tf)
      .setStorageRef(embeddingsRef)

    if (get(configProtoBytes).isDefined)
      model.setConfigProtoBytes($(configProtoBytes))

    model
  }

  def loadSavedModel(): TensorflowWrapper = {

    val wrapper =
      TensorflowWrapper.readZippedSavedModel("/classifier-dl", tags = Array("serve"), initAllTables = true)
    wrapper
  }
}

object NerDLApproach extends DefaultParamsReadable[ClassifierDLApproach]
