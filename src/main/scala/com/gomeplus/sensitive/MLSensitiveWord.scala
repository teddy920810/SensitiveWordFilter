package com.gomeplus.sensitive

import java.net.InetSocketAddress

import com.alibaba.fastjson.JSON
import com.gomeplus.util.Conf
import org.apache.hadoop.fs.Path
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.ml.classification.{NaiveBayes, NaiveBayesModel}
import org.apache.spark.ml.feature.{HashingTF, IDF}
import org.apache.spark.sql.SQLContext
import org.elasticsearch.action.admin.indices.analyze.AnalyzeResponse
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.elasticsearch.spark._
import org.slf4j.LoggerFactory

import scalaj.http.Http

/**
 * Created by wangxiaojing on 16/10/24.
 */

object MLSensitiveWord {

  val loggers = LoggerFactory.getLogger("MLSensitiveWord")
  val hdfsPath = "trainingData"
  // 敏感词的在redis中的主key
  val ikMain = "ik_main"
  val modelDir = "modelDir"
  def main (args: Array[String]){

    //参数解析
    val config = new Conf
    config.parse(args)
    val esHostNames: Array[String] = config.getEsHostname.split(",")
    loggers.debug(esHostNames.toString)
    // 生成es 连接
    val url = "http://"+ esHostNames(0) + "/_analyze"
   // 创建sparkContext
    val sparkConf = new SparkConf()
    sparkConf.set("es.nodes",config.getEsHostname)
    sparkConf.set("es.index.auto.create", "true")
    val sc = new SparkContext(sparkConf)

    //创建Dataframe
    val sqlContext = new SQLContext(sc)
    // 通过hdfs读取的数据作为和es内的数据作为训练数据，训练模型

    //敏感词的lable设置为1.0，非敏感词的lable为0.0
    // 获取es中敏感词数据
    val sensitiveWordIndex = sc.esRDD("gome/word")
    val sensitiveWord = sensitiveWordIndex.map(x=>{
      val words = x._2.getOrElse("word","word").toString
      words
     })

    val size = sensitiveWord.cache().count().toDouble

    // 获取hdfs内的数据作为非敏感词数据进行训练模型
    val unSensitiveWordLine =  sc.textFile(hdfsPath).map(x=>{
      x.replace("@",
        "").replace("?",
        "").replace("!",
        "").replace("//",
        "").replace("\\",
        "").replace("&",
        "").replace("@",
        "").trim
    })
    val unSensitiveWords = unSensitiveWordLine.filter(_.size>0).flatMap(x=>{
      val result = Http(url)
        .param("pretty","true")
        .param("analyzer","ik_smart")
        .param("text",x)
        .asString.body
      val actual = JSON.parseObject(result).getJSONArray("tokens")
      var sensitiveWordList = List(new String)
      for(i<- 0 to actual.size() - 1){
        sensitiveWordList = actual.getString(i) ::sensitiveWordList
      }
      sensitiveWordList
    }).distinct(10)

    //创建一个(word，label) tuples
    val sensitiveWord_2 = sensitiveWord.map(x=>{(Seq(x),1.0)})
    val unSensitiveWords_2 = unSensitiveWords.subtract(sensitiveWord).map(x=>{(Seq(x),0.0)})

    val rdd = sensitiveWord_2.union(unSensitiveWords_2)
    val trainDataFrame = sqlContext.createDataFrame(rdd).toDF("word","label")
    val hashingTF = new  HashingTF().setInputCol("word").setOutputCol("rawFeatures").setNumFeatures(2000)
    val tf = hashingTF.transform(trainDataFrame)
    tf.printSchema()
    val idf = new IDF().setInputCol("rawFeatures").setOutputCol("features")
    val idfModel = idf.fit(tf)
    val tfidf = idfModel.transform(tf)

    tfidf.printSchema()

    // 进行贝叶斯计算
    val nb = new NaiveBayes().setSmoothing(1.0).setModelType("multinomial")
    val model = nb.fit(tfidf)
    // 保存贝叶斯模型
    val path = new Path(modelDir)
    val hadoopConf = sc.hadoopConfiguration
    val hdfs = org.apache.hadoop.fs.FileSystem.get(hadoopConf)
    hdfs.deleteOnExit(path)

    // 保存模型
    model.write.overwrite().save(modelDir)

    /****************************开始测试*****************************/
    if(args.length>0 && args(0).equals("test")){
      loggers.info("Start test this model:")
      val unSensitiveWordsCount = unSensitiveWords.count()
      val testDataFrame = sqlContext.createDataFrame(sensitiveWord_2).toDF("word","label")
      val testtdf = hashingTF.transform(testDataFrame)
      val testidfModel = idf.fit(testtdf)
      val testTfidf = testidfModel.transform(testtdf)

      // 模型验证
      val loadModel = NaiveBayesModel.load(modelDir)
      val out = loadModel.transform(testTfidf)
      val testData = out.select("word","prediction","label").filter("prediction=label").count().toDouble
      val testDataFalse_data = out.select("word","prediction","label").filter("prediction!=label")
      val testDataFalse = testDataFalse_data.count().toDouble
      //testDataFalse_data.foreach(println)
      val truePositive = testDataFalse_data.filter("prediction = 1.0 and label = 0.0" )
      val falseNegative = testDataFalse_data.filter("prediction = 0.0 and label = 1.0" )

      val all = out.count().toDouble
      val num = testData/all*100
      val s = out.filter("prediction = 1.0").count()
      val TP = truePositive.count().toDouble
      val FN = falseNegative.count().toDouble
      val ttot = FN/size*100

      out.select("word","prediction","label").filter("prediction=label and label=1.0").show(100)
      loggers.info("最终输出敏感词个数 ：" +  s + "实际上敏感词个数 " + size + "  非敏感词个数 ： " + unSensitiveWordsCount)
      loggers.info("打标错误，正常词打成敏感词个数："+ TP + " 敏感词打成正常词个数：" + FN+ "错误判断敏感词的概率" + ttot)
      loggers.info("总计： " + all + "正确 ： " + testData + " 错误个数：" + testDataFalse + " 准确率 :" + num + "%")
    }
  }
}
