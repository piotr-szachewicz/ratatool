/*
 * Copyright 2016 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.ratatool.diffy

import com.spotify.ratatool.avro.specific.{RequiredNestedRecord, TestRecord}
import com.spotify.ratatool.scalacheck._
import com.spotify.scio.testing.PipelineSpec
import org.apache.avro.util.Utf8
import org.apache.beam.sdk.coders.AvroCoder
import org.apache.beam.sdk.util.CoderUtils
import org.scalacheck.Gen
import org.scalacheck.rng.Seed

class BigDiffyTest extends PipelineSpec {

  val keys = (1 to 1000).map("key" + _)
  val coder = AvroCoder.of(classOf[TestRecord])

  /** Fixed to a small range so that Std. Dev. & Variance calculations are easier to predict */
  val rnr = specificRecordOf[RequiredNestedRecord]
    .amend(Gen.choose[Double](0.0, 1.0))(_.setDoubleField)
  val specificGen = specificRecordOf[TestRecord]
    .amend(rnr)(_.setRequiredFields)
  val lhs = Gen.listOfN(1000, specificGen)
    .pureApply(Gen.Parameters.default.withSize(10), Seed.random()).zip(1 to 1000)
    .map { case (r, i) =>
      r.getRequiredFields.setIntField(i)
      r.getRequiredFields.setStringField(new Utf8("key" + i))
      r
    }
  val field = "required_fields.double_field"

  "BigDiffy" should "work with identical inputs" in {
    runWithContext { sc =>
      val rhs = lhs.map(CoderUtils.clone(coder, _))
      val result = BigDiffy.diff[TestRecord](
        sc.parallelize(lhs), sc.parallelize(rhs),
        new AvroDiffy[TestRecord](), _.getRequiredFields.getStringField.toString)
      result.globalStats should containSingleValue (GlobalStats(1000L, 1000L, 0L, 0L, 0L))
      result.deltas should beEmpty
      result.keyStats should containInAnyOrder (keys.map(KeyStats(_, DiffType.SAME, None)))
      result.fieldStats should beEmpty
    }
  }

  it should "work with deltas" in {
    runWithContext { sc =>
      val keyedDoubles = lhs.map(i =>
        (i.getRequiredFields.getStringField.toString , i.getRequiredFields.getDoubleField))
      val rhs = lhs.map(CoderUtils.clone(coder, _)).map { r =>
        r.getRequiredFields.setDoubleField(r.getRequiredFields.getDoubleField + 10.0)
        r
      }
      val result = BigDiffy.diff[TestRecord](
        sc.parallelize(lhs), sc.parallelize(rhs),
        new AvroDiffy[TestRecord](), _.getRequiredFields.getStringField.toString)
      result.globalStats should containSingleValue (GlobalStats(1000L, 0L, 1000L, 0L, 0L))
      result.deltas.map(d => (d._1, d._2)) should containInAnyOrder (
        keys.map((_, field)))
      result.keyStats should containInAnyOrder (keyedDoubles.map{case (k, d) =>
        KeyStats(k, DiffType.DIFFERENT, Option(Delta("required_fields.double_field", d, d + 10.0,
          TypedDelta(DeltaType.NUMERIC, 10.0))))})
      result.fieldStats.map(f => (f.field, f.count, f.fraction)) should containSingleValue (
        (field, 1000L, 1.0))
      // Double.NaN comparison is always false
      val deltaStats = result.fieldStats
        .flatMap(_.deltaStats)
        .map(d => (d.deltaType, d.min, d.max, d.count, d.mean, d.variance, d.stddev))
      deltaStats should containSingleValue ((DeltaType.NUMERIC, 10.0, 10.0, 1000L, 10.0, 0.0, 0.0))
    }
  }

  it should "work with missing LHS" in {
    runWithContext { sc =>
      val lhs2 = lhs.filter(_.getRequiredFields.getIntField <= 500)
      val rhs = lhs.map(CoderUtils.clone(coder, _))
      val result = BigDiffy.diff[TestRecord](
        sc.parallelize(lhs2), sc.parallelize(rhs),
        new AvroDiffy[TestRecord](), _.getRequiredFields.getStringField.toString)
      result.globalStats should containSingleValue (GlobalStats(1000L, 500L, 0L, 500L, 0L))
      result.deltas should beEmpty
      result.keyStats should containInAnyOrder (
        (1 to 500).map(i => KeyStats("key" + i, DiffType.SAME, None)) ++
          (501 to 1000).map(i => KeyStats("key" + i, DiffType.MISSING_LHS, None)))
      result.fieldStats should beEmpty
    }
  }

  it should "work with missing RHS" in {
    runWithContext { sc =>
      val rhs = lhs.filter(_.getRequiredFields.getIntField <= 500).map(CoderUtils.clone(coder, _))
      val result = BigDiffy.diff[TestRecord](
        sc.parallelize(lhs), sc.parallelize(rhs),
        new AvroDiffy[TestRecord](), _.getRequiredFields.getStringField.toString)
      result.globalStats should containSingleValue (GlobalStats(1000L, 500L, 0L, 0L, 500L))
      result.deltas should beEmpty
      result.keyStats should containInAnyOrder (
        (1 to 500).map(i => KeyStats("key" + i, DiffType.SAME, None)) ++
          (501 to 1000).map(i => KeyStats("key" + i, DiffType.MISSING_RHS, None)))
      result.fieldStats should beEmpty
    }
  }

}
