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

package org.apache.livy.server.batch

import java.io.{ByteArrayInputStream, FileWriter}
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.Duration

import org.mockito.Matchers
import org.mockito.Matchers.anyObject
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfter, FunSpec, ShouldMatchers}
import org.scalatest.mock.MockitoSugar.mock

import org.apache.livy.{LivyBaseUnitTestSuite, LivyConf, Utils}
import org.apache.livy.server.recovery.SessionStore
import org.apache.livy.sessions.SessionState
import org.apache.livy.sessions.SessionState.{NotStarted, Starting}
import org.apache.livy.utils.{AppInfo, SparkApp}

class BatchSessionSpec
  extends FunSpec
  with BeforeAndAfter
  with ShouldMatchers
  with LivyBaseUnitTestSuite {

  val script: Path = {
    val script = Files.createTempFile("livy-test", ".py")
    script.toFile.deleteOnExit()
    val writer = new FileWriter(script.toFile)
    try {
      writer.write(
        """
          |print "hello world"
        """.stripMargin)
    } finally {
      writer.close()
    }
    script
  }

  describe("A Batch process") {
    var sessionStore: SessionStore = null
    val tmpDir = sys.props("java.io.tmpdir")

    before {
      sessionStore = mock[SessionStore]
    }

    it("should create a process") {
      val req = new CreateBatchRequest()
      req.file = script.toString
      req.conf = Map("spark.driver.extraClassPath" -> sys.props("java.class.path"))

      val conf = new LivyConf().set(LivyConf.LOCAL_FS_WHITELIST, tmpDir)
      val batch = BatchSession.create(0, req, conf, null, None, sessionStore)

      Utils.waitUntil({ () => !batch.state.isActive }, Duration(10, TimeUnit.SECONDS))
      (batch.state match {
        case SessionState.Success(_) => true
        case _ => false
      }) should be (true)

      batch.logLines() should contain("hello world")
    }

    it("should update appId and appInfo") {
      val conf = new LivyConf()
      val req = new CreateBatchRequest()
      val mockApp = mock[SparkApp]
      val batch = BatchSession.create(0, req, conf, null, None, sessionStore, Some(mockApp))

      val expectedAppId = "APPID"
      batch.appIdKnown(expectedAppId)
      verify(sessionStore, atLeastOnce()).save(
        Matchers.eq(BatchSession.RECOVERY_SESSION_TYPE), anyObject())
      batch.appId shouldEqual Some(expectedAppId)

      val expectedAppInfo = AppInfo(Some("DRIVER LOG URL"), Some("SPARK UI URL"))
      batch.infoChanged(expectedAppInfo)
      batch.appInfo shouldEqual expectedAppInfo
    }

    it("should recover session") {
      val conf = new LivyConf()
      val req = new CreateBatchRequest()
      val mockApp = mock[SparkApp]
      val m = BatchRecoveryMetadata(99, None, "appTag", null, None)
      val batch = BatchSession.recover(m, conf, sessionStore, Some(mockApp))

      batch.state shouldBe (SessionState.Recovering)

      batch.appIdKnown("appId")
      verify(sessionStore, atLeastOnce()).save(
        Matchers.eq(BatchSession.RECOVERY_SESSION_TYPE), anyObject())
    }

    it("should upload file and create delayed process") {
      val req = new CreateBatchRequest()
      req.delayed = Some("true")
      req.conf = Map("spark.driver.extraClassPath" -> sys.props("java.class.path"))

      val conf = new LivyConf().set(LivyConf.LOCAL_FS_WHITELIST, tmpDir)
        .set(LivyConf.SESSION_STAGING_DIR, tmpDir)
      val batch = BatchSession.create(0, req,
        conf, null, None, sessionStore)

      batch.state should be(NotStarted)

      val fileName = script.getFileName.toString
      batch.setFile(new ByteArrayInputStream(Files.readAllBytes(script)), fileName)
      req.file should not be empty

      val startedBatch = batch.startDelayed()
      startedBatch.state should be(Starting)

      Utils.waitUntil({ () => !startedBatch.state.isActive }, Duration(10, TimeUnit.SECONDS))
      (startedBatch.state match {
        case SessionState.Success(_) => true
        case _ => false
      }) should be (true)

      startedBatch.logLines() should contain("hello world")
    }
  }
}
