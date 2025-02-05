/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.geode.management.internal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.google.common.collect.Sets;
import org.junit.Before;
import org.junit.Test;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.configuration.CacheConfig;
import org.apache.geode.cache.configuration.GatewayReceiverConfig;
import org.apache.geode.cache.configuration.RegionConfig;
import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.distributed.internal.InternalConfigurationPersistenceService;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.internal.config.JAXBService;
import org.apache.geode.management.api.ClusterManagementException;
import org.apache.geode.management.api.ClusterManagementOperation;
import org.apache.geode.management.api.ClusterManagementOperationResult;
import org.apache.geode.management.api.ClusterManagementRealizationResult;
import org.apache.geode.management.api.ClusterManagementResult;
import org.apache.geode.management.api.RealizationResult;
import org.apache.geode.management.configuration.MemberConfig;
import org.apache.geode.management.internal.CacheElementOperation;
import org.apache.geode.management.internal.ClusterManagementOperationStatusResult;
import org.apache.geode.management.internal.configuration.mutators.ConfigurationManager;
import org.apache.geode.management.internal.configuration.mutators.GatewayReceiverConfigManager;
import org.apache.geode.management.internal.configuration.mutators.RegionConfigManager;
import org.apache.geode.management.internal.configuration.validators.CacheElementValidator;
import org.apache.geode.management.internal.configuration.validators.ConfigurationValidator;
import org.apache.geode.management.internal.configuration.validators.MemberValidator;
import org.apache.geode.management.internal.configuration.validators.RegionConfigValidator;
import org.apache.geode.management.internal.operation.OperationHistoryManager.OperationInstance;
import org.apache.geode.management.internal.operation.OperationManager;
import org.apache.geode.management.runtime.OperationResult;

public class LocatorClusterManagementServiceTest {

  private LocatorClusterManagementService service;
  private InternalCache cache;
  private InternalConfigurationPersistenceService persistenceService;
  private RegionConfig regionConfig;
  private ClusterManagementResult result;
  private Map<Class, ConfigurationValidator> validators = new HashMap<>();
  private Map<Class, ConfigurationManager> managers = new HashMap<>();
  private OperationManager executorManager;
  private ConfigurationValidator<RegionConfig> regionValidator;
  private CacheElementValidator cacheElementValidator;
  private ConfigurationManager<RegionConfig> regionManager;
  private MemberValidator memberValidator;

  @Before
  public void before() throws Exception {
    cache = mock(InternalCache.class);
    regionValidator = mock(RegionConfigValidator.class);
    doCallRealMethod().when(regionValidator).validate(eq(CacheElementOperation.DELETE), any());
    regionManager = spy(RegionConfigManager.class);
    cacheElementValidator = spy(CacheElementValidator.class);
    validators.put(RegionConfig.class, regionValidator);
    managers.put(RegionConfig.class, regionManager);
    managers.put(GatewayReceiverConfig.class, new GatewayReceiverConfigManager());

    memberValidator = mock(MemberValidator.class);

    persistenceService = spy(new InternalConfigurationPersistenceService(
        JAXBService.create(CacheConfig.class)));

    Set<String> groups = new HashSet<>();
    groups.add("cluster");
    doReturn(groups).when(persistenceService).getGroups();
    doReturn(new CacheConfig()).when(persistenceService).getCacheConfig(any(), anyBoolean());
    doReturn(true).when(persistenceService).lockSharedConfiguration();
    doNothing().when(persistenceService).unlockSharedConfiguration();
    executorManager = mock(OperationManager.class);
    service =
        spy(new LocatorClusterManagementService(persistenceService, managers, validators,
            memberValidator, cacheElementValidator, executorManager));
    regionConfig = new RegionConfig();
    regionConfig.setName("region1");
  }

  @Test
  public void create_persistenceIsNull() {
    service = new LocatorClusterManagementService(cache, null);
    assertThatThrownBy(() -> service.create(regionConfig))
        .hasMessageContaining("Cluster configuration service needs to be enabled");
  }

  @Test
  public void create_validatorIsCalledCorrectly() {
    doReturn(Collections.emptySet()).when(memberValidator).findServers(anyString());
    doNothing().when(persistenceService).updateCacheConfig(any(), any());
    service.create(regionConfig);
    verify(cacheElementValidator).validate(CacheElementOperation.CREATE, regionConfig);
    verify(regionValidator).validate(CacheElementOperation.CREATE, regionConfig);
    verify(memberValidator).validateCreate(regionConfig, regionManager);
  }

  @Test
  public void delete_validatorIsCalledCorrectly() {
    doReturn(Collections.emptySet()).when(memberValidator).findServers(anyString());
    doReturn(new String[] {"cluster"}).when(memberValidator).findGroupsWithThisElement(
        regionConfig.getId(),
        regionManager);
    doNothing().when(persistenceService).updateCacheConfig(any(), any());
    service.delete(regionConfig);
    verify(cacheElementValidator).validate(CacheElementOperation.DELETE, regionConfig);
    verify(regionValidator).validate(CacheElementOperation.DELETE, regionConfig);
    verify(memberValidator).findGroupsWithThisElement(regionConfig.getId(), regionManager);
    verify(memberValidator).findServers("cluster");
  }

  @Test
  public void create_partialFailureOnMembers() {
    List<RealizationResult> functionResults = new ArrayList<>();
    functionResults.add(new RealizationResult().setMemberName("member1"));
    functionResults.add(
        new RealizationResult().setMemberName("member2").setSuccess(false).setMessage("failed"));
    doReturn(functionResults).when(service).executeAndGetFunctionResult(any(), any(), any());

    doReturn(Collections.singleton(mock(DistributedMember.class))).when(memberValidator)
        .findServers();

    when(persistenceService.getCacheConfig("cluster", true)).thenReturn(new CacheConfig());
    regionConfig.setName("test");
    assertThatThrownBy(() -> service.create(regionConfig))
        .hasMessageContaining("Failed to create on all members");
  }

  @Test
  public void create_succeedsOnAllMembers() {
    List<RealizationResult> functionResults = new ArrayList<>();
    functionResults.add(new RealizationResult().setMemberName("member1"));
    functionResults.add(new RealizationResult().setMemberName("member2"));
    doReturn(functionResults).when(service).executeAndGetFunctionResult(any(), any(), any());

    doReturn(Collections.singleton(mock(DistributedMember.class))).when(memberValidator)
        .findServers();

    CacheConfig cacheConfig = new CacheConfig();
    when(persistenceService.getCacheConfig("cluster", true)).thenReturn(cacheConfig);
    doReturn(null).when(persistenceService).getConfiguration(any());
    Region mockRegion = mock(Region.class);
    doReturn(mockRegion).when(persistenceService).getConfigurationRegion();

    regionConfig.setName("test");
    result = service.create(regionConfig);
    assertThat(result.isSuccessful()).isTrue();

    assertThat(cacheConfig.getRegions()).hasSize(1);
  }

  @Test
  public void create_non_supportedConfigObject() {
    MemberConfig config = new MemberConfig();
    assertThatThrownBy(() -> service.create(config)).isInstanceOf(ClusterManagementException.class)
        .hasMessageContaining("ILLEGAL_ARGUMENT: MemberConfig is not supported.");
  }

  @Test
  public void list_oneGroup() {
    regionConfig.setGroup("cluster");
    doReturn(Sets.newHashSet("cluster", "group1")).when(persistenceService).getGroups();

    service.list(regionConfig);
    // even we are listing regions in one group, we still need to go through all the groups
    verify(persistenceService).getCacheConfig("cluster", true);
    verify(persistenceService).getCacheConfig("group1", true);
    verify(regionManager, times(2)).list(any(), any());
  }

  @Test
  public void list_aRegionInClusterAndGroup1() {
    doReturn(Sets.newHashSet("cluster", "group1")).when(persistenceService).getGroups();
    RegionConfig region1 = new RegionConfig();
    region1.setName("region1");
    region1.setType("REPLICATE");
    RegionConfig region2 = new RegionConfig();
    region2.setName("region1");
    region2.setType("REPLICATE");

    List clusterRegions = Arrays.asList(region1);
    List group1Regions = Arrays.asList(region2);
    doReturn(clusterRegions, group1Regions).when(regionManager).list(any(), any());

    // this is to make sure when 'cluster" is in one of the group, it will show
    // the cluster and the other group name
    List<RegionConfig> results =
        service.list(new RegionConfig()).getConfigResult();
    assertThat(results).hasSize(1);
    RegionConfig result = results.get(0);
    assertThat(result.getName()).isEqualTo("region1");
    assertThat(result.getGroups()).containsExactlyInAnyOrder("cluster", "group1");
  }

  @Test
  public void delete_unknownRegionFails() {
    RegionConfig config = new RegionConfig();
    config.setName("unknown");
    doReturn(new String[] {}).when(memberValidator).findGroupsWithThisElement(any(), any());
    assertThatThrownBy(() -> service.delete(config))
        .isInstanceOf(ClusterManagementException.class)
        .hasMessage("ENTITY_NOT_FOUND: RegionConfig 'unknown' does not exist.");
  }

  @Test
  public void delete_usingGroupFails() {
    RegionConfig config = new RegionConfig();
    config.setName("test");
    config.setGroup("group1");
    assertThatThrownBy(() -> service.delete(config))
        .isInstanceOf(ClusterManagementException.class)
        .hasMessage("ILLEGAL_ARGUMENT: Group is an invalid option when deleting region.");
  }

  @Test
  public void delete_partialFailureOnMembers() {
    List<RealizationResult> functionResults = new ArrayList<>();
    functionResults.add(new RealizationResult().setMemberName("member1"));
    functionResults.add(
        new RealizationResult().setMemberName("member2").setSuccess(false).setMessage("failed"));
    doReturn(functionResults).when(service).executeAndGetFunctionResult(any(), any(), any());

    doReturn(new String[] {"cluster"}).when(memberValidator).findGroupsWithThisElement(any(),
        any());
    doReturn(Collections.singleton(mock(DistributedMember.class))).when(memberValidator)
        .findServers();

    CacheConfig config = new CacheConfig();
    RegionConfig regionConfig = new RegionConfig();
    regionConfig.setName("test");
    config.getRegions().add(regionConfig);
    doReturn(config).when(persistenceService).getCacheConfig(eq("cluster"), anyBoolean());

    result = service.delete(regionConfig);
    assertThat(result.isSuccessful()).isFalse();
    assertThat(result.getStatusMessage())
        .contains("Failed to delete on all members.");

    assertThat(config.getRegions()).hasSize(1);
  }

  @Test
  public void delete_succeedsOnAllMembers() {
    List<RealizationResult> functionResults = new ArrayList<>();
    functionResults.add(new RealizationResult().setMemberName("member1"));
    functionResults.add(new RealizationResult().setMemberName("member2"));
    doReturn(functionResults).when(service).executeAndGetFunctionResult(any(), any(), any());

    doReturn(new String[] {"cluster"}).when(memberValidator).findGroupsWithThisElement(any(),
        any());
    doReturn(Collections.singleton(mock(DistributedMember.class))).when(memberValidator)
        .findServers();

    CacheConfig config = new CacheConfig();
    RegionConfig regionConfig = new RegionConfig();
    regionConfig.setName("test");
    config.getRegions().add(regionConfig);
    doReturn(config).when(persistenceService).getCacheConfig(eq("cluster"), anyBoolean());
    doReturn(null).when(persistenceService).getConfiguration(any());
    Region mockRegion = mock(Region.class);
    doReturn(mockRegion).when(persistenceService).getConfigurationRegion();

    result = service.delete(regionConfig);
    assertThat(result.isSuccessful()).isTrue();

    assertThat(config.getRegions()).isEmpty();
  }

  @Test
  public void deleteWithNoMember() {
    // region exists in cluster configuration
    doReturn(new String[] {"cluster"}).when(memberValidator).findGroupsWithThisElement(any(),
        any());
    // no members found in any group
    doReturn(Collections.emptySet()).when(memberValidator).findServers();
    doReturn(null).when(persistenceService).getConfiguration(any());
    Region mockRegion = mock(Region.class);
    doReturn(mockRegion).when(persistenceService).getConfigurationRegion();

    ClusterManagementRealizationResult result = service.delete(regionConfig);
    verify(regionManager).delete(eq(regionConfig), any());
    assertThat(result.isSuccessful()).isTrue();
    assertThat(result.getMemberStatuses()).hasSize(0);
    assertThat(result.getStatusMessage())
        .contains("Successfully removed configuration for [cluster]");
  }

  @Test
  public void startOperation() {
    final String URI = "test/uri";
    ClusterManagementOperation<OperationResult> operation = mock(ClusterManagementOperation.class);
    when(operation.getEndpoint()).thenReturn(URI);
    when(executorManager.submit(any()))
        .thenReturn(new OperationInstance<>(null, "42", operation, new Date()));
    ClusterManagementOperationResult<?> result = service.start(operation);
    assertThat(result.getUri()).isEqualTo("/management/experimental" + URI + "/42");
    assertThat(result.getStatusCode()).isEqualTo(ClusterManagementResult.StatusCode.ACCEPTED);
    assertThat(result.getStatusMessage()).contains("Operation started");
  }

  @Test
  public void checkStatusForNotFound() {
    assertThatThrownBy(() -> service.checkStatus("123"))
        .isInstanceOf(ClusterManagementException.class);
  }

  @Test
  public void checkStatus() {
    CompletableFuture future = mock(CompletableFuture.class);
    OperationInstance operationInstance = mock(OperationInstance.class);
    when(operationInstance.getFutureResult()).thenReturn(future);
    when(operationInstance.getFutureOperationEnded()).thenReturn(future);
    when(executorManager.getOperationInstance(any())).thenReturn(operationInstance);
    when(future.isDone()).thenReturn(false);
    ClusterManagementOperationStatusResult<OperationResult> result = service.checkStatus("456");
    assertThat(result.getStatusCode()).isEqualTo(ClusterManagementResult.StatusCode.IN_PROGRESS);
    assertThat(result.getResult()).isNull();

    when(future.isDone()).thenReturn(true);
    result = service.checkStatus("456");
    assertThat(result.getStatusCode()).isEqualTo(ClusterManagementResult.StatusCode.OK);
  }
}
