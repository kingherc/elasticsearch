// Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
// or more contributor license agreements. Licensed under the Elastic License
// 2.0; you may not use this file except in compliance with the Elastic License
// 2.0.
package org.elasticsearch.compute.aggregation;

import java.lang.Integer;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import org.elasticsearch.compute.operator.DriverContext;

/**
 * {@link AggregatorFunctionSupplier} implementation for {@link TopIpAggregator}.
 * This class is generated. Edit {@code AggregatorFunctionSupplierImplementer} instead.
 */
public final class TopIpAggregatorFunctionSupplier implements AggregatorFunctionSupplier {
  private final int limit;

  private final boolean ascending;

  public TopIpAggregatorFunctionSupplier(int limit, boolean ascending) {
    this.limit = limit;
    this.ascending = ascending;
  }

  @Override
  public List<IntermediateStateDesc> nonGroupingIntermediateStateDesc() {
    return TopIpAggregatorFunction.intermediateStateDesc();
  }

  @Override
  public List<IntermediateStateDesc> groupingIntermediateStateDesc() {
    return TopIpGroupingAggregatorFunction.intermediateStateDesc();
  }

  @Override
  public TopIpAggregatorFunction aggregator(DriverContext driverContext, List<Integer> channels) {
    return TopIpAggregatorFunction.create(driverContext, channels, limit, ascending);
  }

  @Override
  public TopIpGroupingAggregatorFunction groupingAggregator(DriverContext driverContext,
      List<Integer> channels) {
    return TopIpGroupingAggregatorFunction.create(channels, driverContext, limit, ascending);
  }

  @Override
  public String describe() {
    return "top of ips";
  }
}
