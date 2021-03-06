/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution

import scala.util.Random

import org.apache.spark._
import org.apache.spark.sql.{RandomDataGenerator, Row}
import org.apache.spark.sql.catalyst.{CatalystTypeConverters, InternalRow}
import org.apache.spark.sql.catalyst.expressions.{InterpretedOrdering, UnsafeRow, UnsafeProjection}
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.memory.{ExecutorMemoryManager, MemoryAllocator, TaskMemoryManager}

/**
 * Test suite for [[UnsafeKVExternalSorter]], with randomly generated test data.
 * 测试套件[unsafekvexternalsorter],随机生成的测试数据。
 */
class UnsafeKVExternalSorterSuite extends SparkFunSuite with SharedSQLContext {
  private val keyTypes = Seq(IntegerType, FloatType, DoubleType, StringType)
  private val valueTypes = Seq(IntegerType, FloatType, DoubleType, StringType)
//StructType代表一张表,StructField代表一个字段
  testKVSorter(new StructType, new StructType, spill = true)
  testKVSorter(new StructType().add("c1", IntegerType), new StructType, spill = true)
  testKVSorter(new StructType, new StructType().add("c1", IntegerType), spill = true)

  private val rand = new Random(42)
  for (i <- 0 until 6) {
    val keySchema = RandomDataGenerator.randomSchema(rand.nextInt(10) + 1, keyTypes)
    val valueSchema = RandomDataGenerator.randomSchema(rand.nextInt(10) + 1, valueTypes)
    testKVSorter(keySchema, valueSchema, spill = i > 3)
  }


  /**
   * Create a test case using randomly generated data for the given key and value schema.
   * 使用给定的键和值模式随机生成的数据创建一个测试用例
   * The approach works as follows:
   * 该方法的工作原理如下：
   * - Create input by randomly generating data based on the given schema
   *  用输入随机生成的数据的基础上给出了模式
   * - Run [[UnsafeKVExternalSorter]] on the generated data
   * 	运行[unsafekvexternalsorter]上产生的数据
   * - Collect the output from the sorter, and make sure the keys are sorted in ascending order
   *   从分类收集输出,并确保钥匙在升序排序
   * - Sort the input by both key and value, and sort the sorter output also by both key and value.
   *   Compare the sorted input and sorted output together to make sure all the key/values match.
   *   由键和值输入排序,排序分类器的输出也由键和值,比较排序的输入和排序输出一起确保所有的键/值匹配。
   *
   * If spill is set to true, the sorter will spill probabilistically roughly every 100 records.
   * 如果泄漏被设置为true,分类将泄漏的概率大约每100条记录
   */
  private def testKVSorter(keySchema: StructType, valueSchema: StructType, spill: Boolean): Unit = {
    // Create the data converters 创建数据转换器
    val kExternalConverter = CatalystTypeConverters.createToCatalystConverter(keySchema)
    val vExternalConverter = CatalystTypeConverters.createToCatalystConverter(valueSchema)
    val kConverter = UnsafeProjection.create(keySchema)
    val vConverter = UnsafeProjection.create(valueSchema)

    val keyDataGen = RandomDataGenerator.forType(keySchema, nullable = false).get
    val valueDataGen = RandomDataGenerator.forType(valueSchema, nullable = false).get

    val inputData = Seq.fill(1024) {
      val k = kConverter(kExternalConverter.apply(keyDataGen.apply()).asInstanceOf[InternalRow])
      val v = vConverter(vExternalConverter.apply(valueDataGen.apply()).asInstanceOf[InternalRow])
      (k.asInstanceOf[InternalRow].copy(), v.asInstanceOf[InternalRow].copy())
    }

    val keySchemaStr = keySchema.map(_.dataType.simpleString).mkString("[", ",", "]")
    val valueSchemaStr = valueSchema.map(_.dataType.simpleString).mkString("[", ",", "]")

    test(s"kv sorting key schema $keySchemaStr and value schema $valueSchemaStr") {
      testKVSorter(
        keySchema,
        valueSchema,
        inputData,
        pageSize = 16 * 1024 * 1024,
        spill
      )
    }
  }

  /**
   * Create a test case using the given input data for the given key and value schema.
   * 使用给定的键和值模式的给定输入数据创建一个测试用例
   * The approach works as follows:
   * 该方法的工作原理如下：
   * - Create input by randomly generating data based on the given schema
   *   根据给定的模式随机生成数据
   * - Run [[UnsafeKVExternalSorter]] on the input data 运行[unsafekvexternalsorter]对输入数据
   * - Collect the output from the sorter, and make sure the keys are sorted in ascending order
   *   从分类收集输出,并确保Key在升序排序
   * - Sort the input by both key and value, and sort the sorter output also by both key and value.
   * 	 由键和值输入排序,排序分类的输出也由键和值
   *   Compare the sorted input and sorted output together to make sure all the key/values match.
   *   将排序的输入和排序输出进行比较,以确保所有的键/值匹配
   * If spill is set to true, the sorter will spill probabilistically roughly every 100 records.
   *  如果泄漏被设置为true,分类将泄漏的概率大约每100条记录
   */
  private def testKVSorter(
      keySchema: StructType,
      valueSchema: StructType,
      inputData: Seq[(InternalRow, InternalRow)],
      pageSize: Long,
      spill: Boolean): Unit = {

    val taskMemMgr = new TaskMemoryManager(new ExecutorMemoryManager(MemoryAllocator.HEAP))
    val shuffleMemMgr = new TestShuffleMemoryManager
    TaskContext.setTaskContext(new TaskContextImpl(
      stageId = 0,
      partitionId = 0,
      taskAttemptId = 98456,
      attemptNumber = 0,
      taskMemoryManager = taskMemMgr,
      metricsSystem = null,
      internalAccumulators = Seq.empty))

    val sorter = new UnsafeKVExternalSorter(
      keySchema, valueSchema, SparkEnv.get.blockManager, shuffleMemMgr, pageSize)

    // Insert the keys and values into the sorter
    //插入键和值的分类
    inputData.foreach { case (k, v) =>
      sorter.insertKV(k.asInstanceOf[UnsafeRow], v.asInstanceOf[UnsafeRow])
      // 1% chance we will spill
      //1%的机会,我们将溢出
      if (rand.nextDouble() < 0.01 && spill) {
        shuffleMemMgr.markAsOutOfMemory()
        sorter.closeCurrentPage()
      }
    }

    // Collect the sorted output 收集排序输出
    val out = new scala.collection.mutable.ArrayBuffer[(InternalRow, InternalRow)]
    val iter = sorter.sortedIterator()
    while (iter.next()) {
      out += Tuple2(iter.getKey.copy(), iter.getValue.copy())
    }
    sorter.cleanupResources()

    val keyOrdering = InterpretedOrdering.forSchema(keySchema.map(_.dataType))
    val valueOrdering = InterpretedOrdering.forSchema(valueSchema.map(_.dataType))
    val kvOrdering = new Ordering[(InternalRow, InternalRow)] {
      override def compare(x: (InternalRow, InternalRow), y: (InternalRow, InternalRow)): Int = {
        keyOrdering.compare(x._1, y._1) match {
          case 0 => valueOrdering.compare(x._2, y._2)
          case cmp => cmp
        }
      }
    }

    // Testing to make sure output from the sorter is sorted by key
    //测试从分类确保输出排序的关键
    var prevK: InternalRow = null
    out.zipWithIndex.foreach { case ((k, v), i) =>
      if (prevK != null) {
        assert(keyOrdering.compare(prevK, k) <= 0,
          s"""
             |key is not in sorted order:
             |previous key: $prevK
             |current key : $k
             """.stripMargin)
      }
      prevK = k
    }

    // Testing to make sure the key/value in output matches input
    //测试以确保输入输出的键/值匹配输入
    assert(out.sorted(kvOrdering) === inputData.sorted(kvOrdering))

    // Make sure there is no memory leak
    //确保没有内存泄漏
    val leakedUnsafeMemory: Long = taskMemMgr.cleanUpAllAllocatedMemory
    if (shuffleMemMgr != null) {
      val leakedShuffleMemory: Long = shuffleMemMgr.getMemoryConsumptionForThisTask()
      assert(0L === leakedShuffleMemory)
    }
    assert(0 === leakedUnsafeMemory)
    TaskContext.unset()
  }
  //记录超过页大小的记录
  test("kv sorting with records that exceed page size") {
    val pageSize = 128
  //StructType代表一张表,StructField代表一个字段
    val schema = StructType(StructField("b", BinaryType) :: Nil)
    val externalConverter = CatalystTypeConverters.createToCatalystConverter(schema)
    val converter = UnsafeProjection.create(schema)

    val rand = new Random()
    val inputData = Seq.fill(1024) {
      val kBytes = new Array[Byte](rand.nextInt(pageSize))
      val vBytes = new Array[Byte](rand.nextInt(pageSize))
      rand.nextBytes(kBytes)
      rand.nextBytes(vBytes)
      val k = converter(externalConverter.apply(Row(kBytes)).asInstanceOf[InternalRow])
      val v = converter(externalConverter.apply(Row(vBytes)).asInstanceOf[InternalRow])
      (k.asInstanceOf[InternalRow].copy(), v.asInstanceOf[InternalRow].copy())
    }

    testKVSorter(
      schema,
      schema,
      inputData,
      pageSize,
      spill = true
    )
  }
}
