/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v2_1.planner.logical.steps

import org.neo4j.cypher.internal.compiler.v2_1.planner.logical._
import org.neo4j.cypher.internal.compiler.v2_1.planner.logical.LogicalPlanContext
import org.neo4j.cypher.internal.compiler.v2_1.planner.{CantHandleQueryException, Exists}
import org.neo4j.cypher.internal.compiler.v2_1.planner.logical.plans.{SimplePatternLength, SemiApply, LogicalPlan}
import org.neo4j.cypher.internal.helpers.Converge.iterateUntilConverged

case class selectPatternPredicates(simpleSelection: PlanTransformer) extends PlanTransformer {
  private object candidateListProducer extends CandidateGenerator[PlanTable] {
    def apply(planTable: PlanTable)(implicit context: LogicalPlanContext): CandidateList = {
      val applyCandidates =
        for (pattern <- context.queryGraph.patternPredicates;
             lhs <- planTable.plans if applicable(lhs, pattern))
        yield {
          val rhs = context.strategy.plan(context.copy(queryGraph = pattern.queryGraph))
          SemiApply(lhs, rhs)(pattern)
        }
      CandidateList(applyCandidates)
    }

    private def applicable(outerPlan: LogicalPlan, inner: Exists) = {
      val providedIds = outerPlan.coveredIds
      val hasDependencies = inner.queryGraph.argumentIds.forall(providedIds.contains)
      val isSolved = outerPlan.solved.selections.contains(inner.predicate.exp)
      hasDependencies && !isSolved
    }
  }

  def apply(input: LogicalPlan)(implicit context: LogicalPlanContext): LogicalPlan = {
    val plan = simpleSelection(input)

    def findBestPlanForPatternPredicates(plan: LogicalPlan): LogicalPlan = {
      val secretPlanTable = PlanTable(Map(plan.coveredIds -> plan))
      val result: CandidateList = candidateListProducer(secretPlanTable)
      result.bestPlan(context.cost).getOrElse(plan)
    }

    iterateUntilConverged(findBestPlanForPatternPredicates)(plan)
  }
}
