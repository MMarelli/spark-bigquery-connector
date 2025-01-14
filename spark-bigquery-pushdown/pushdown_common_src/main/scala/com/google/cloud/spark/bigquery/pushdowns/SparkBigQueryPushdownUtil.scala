/*
 * Copyright 2022 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.spark.bigquery.pushdowns

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, Expression, NamedExpression}

/**
 * Static methods for query pushdown functionality
 */
object SparkBigQueryPushdownUtil {
  def enableBigQueryStrategy(session: SparkSession, bigQueryStrategy: BigQueryStrategy): Unit = {
    if (!session.experimental.extraStrategies.exists(
      s => s.isInstanceOf[BigQueryStrategy]
    )) {
      session.experimental.extraStrategies ++= Seq(bigQueryStrategy)
    }
  }

  def disableBigQueryStrategy(session: SparkSession): Unit = {
    session.experimental.extraStrategies = session.experimental.extraStrategies
      .filterNot(strategy => strategy.isInstanceOf[BigQueryStrategy])
  }

  /**
   * Creates a new BigQuerySQLStatement by adding parenthesis around the passed in statement and adding an alias for it
   * @param stmt
   * @param alias
   * @return
   */
  def blockStatement(stmt: BigQuerySQLStatement, alias: String): BigQuerySQLStatement =
    blockStatement(stmt) + "AS" + ConstantString(alias.toUpperCase).toStatement

  def blockStatement(stmt: BigQuerySQLStatement): BigQuerySQLStatement =
    ConstantString("(") + stmt + ")"

  /**
   * Creates a new BigQuerySQLStatement by taking a list of BigQuerySQLStatement and folding it into one BigQuerySQLStatement separated by a delimiter
   * @param seq
   * @param delimiter
   * @return
   */
  def makeStatement(seq: Seq[BigQuerySQLStatement], delimiter: String): BigQuerySQLStatement =
    makeStatement(seq, ConstantString(delimiter).toStatement)

  def makeStatement(seq: Seq[BigQuerySQLStatement], delimiter: BigQuerySQLStatement): BigQuerySQLStatement =
    seq.foldLeft(EmptyBigQuerySQLStatement()) {
      case (left, stmt) =>
        if (left.isEmpty) stmt else left + delimiter + stmt
    }

  /** This adds an attribute as part of a SQL expression, searching in the provided
   * fields for a match, so the subquery qualifiers are correct.
   *
   * @param attr   The Spark Attribute object to be added.
   * @param fields The Seq[Attribute] containing the valid fields to be used in the expression,
   *               usually derived from the output of a subquery.
   * @return A BigQuerySQLStatement representing the attribute expression.
   */
  def addAttributeStatement(attr: Attribute, fields: Seq[Attribute]): BigQuerySQLStatement =
    fields.find(e => e.exprId == attr.exprId) match {
      case Some(resolved) =>
        qualifiedAttributeStatement(resolved.qualifier, resolved.name)
      case None => qualifiedAttributeStatement(attr.qualifier, attr.name)
    }

  def qualifiedAttributeStatement(alias: Seq[String], name: String): BigQuerySQLStatement =
    ConstantString(qualifiedAttribute(alias, name)).toStatement

  def qualifiedAttribute(alias: Seq[String], name: String): String = {
    val str =
      if (alias.isEmpty) ""
      else alias.map(a => a.toUpperCase).mkString(".") + "."

    str + name.toUpperCase
  }

  /**
   * Rename projections so as to have unique column names across subqueries
   * @param origOutput
   * @param alias
   * @return
   */
  def renameColumns(origOutput: Seq[NamedExpression], alias: String, expressionFactory: SparkExpressionFactory): Seq[NamedExpression] = {
    val col_names = Iterator.from(0).map(n => s"COL_$n")

    origOutput.map { expr =>
      val metadata = expr.metadata

      val altName = s"""${alias}_${col_names.next()}"""

      expr match {
        case a@Alias(child: Expression, name: String) =>
          expressionFactory.createAlias(child, altName, a.exprId, Seq.empty[String], Some(metadata))
        case _ =>
          expressionFactory.createAlias(expr, altName, expr.exprId, Seq.empty[String], Some(metadata))
      }
    }
  }
}
