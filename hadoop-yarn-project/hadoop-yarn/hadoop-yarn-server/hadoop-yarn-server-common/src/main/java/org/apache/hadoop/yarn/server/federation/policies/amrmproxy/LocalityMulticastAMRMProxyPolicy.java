/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.federation.policies.amrmproxy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.collections.MapUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.api.records.EnhancedHeadroom;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.exceptions.YarnRuntimeException;
import org.apache.hadoop.yarn.server.federation.policies.FederationPolicyInitializationContext;
import org.apache.hadoop.yarn.server.federation.policies.FederationPolicyUtils;
import org.apache.hadoop.yarn.server.federation.policies.dao.WeightedPolicyInfo;
import org.apache.hadoop.yarn.server.federation.policies.exceptions.FederationPolicyException;
import org.apache.hadoop.yarn.server.federation.policies.exceptions.FederationPolicyInitializationException;
import org.apache.hadoop.yarn.server.federation.policies.exceptions.NoActiveSubclustersException;
import org.apache.hadoop.yarn.server.federation.resolver.SubClusterResolver;
import org.apache.hadoop.yarn.server.federation.store.records.SubClusterId;
import org.apache.hadoop.yarn.server.federation.store.records.SubClusterIdInfo;
import org.apache.hadoop.yarn.server.federation.store.records.SubClusterInfo;
import org.apache.hadoop.yarn.server.federation.utils.FederationStateStoreFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.classification.VisibleForTesting;
import org.apache.hadoop.util.Preconditions;

import static org.apache.hadoop.yarn.conf.YarnConfiguration.LOAD_BASED_SC_SELECTOR_ENABLED;
import static org.apache.hadoop.yarn.conf.YarnConfiguration.DEFAULT_LOAD_BASED_SC_SELECTOR_ENABLED;
import static org.apache.hadoop.yarn.conf.YarnConfiguration.LOAD_BASED_SC_SELECTOR_THRESHOLD;
import static org.apache.hadoop.yarn.conf.YarnConfiguration.DEFAULT_LOAD_BASED_SC_SELECTOR_THRESHOLD;
import static org.apache.hadoop.yarn.conf.YarnConfiguration.LOAD_BASED_SC_SELECTOR_USE_ACTIVE_CORE;
import static org.apache.hadoop.yarn.conf.YarnConfiguration.DEFAULT_LOAD_BASED_SC_SELECTOR_USE_ACTIVE_CORE;
import static org.apache.hadoop.yarn.conf.YarnConfiguration.LOAD_BASED_SC_SELECTOR_MULTIPLIER;
import static org.apache.hadoop.yarn.conf.YarnConfiguration.DEFAULT_LOAD_BASED_SC_SELECTOR_MULTIPLIER;
import static org.apache.hadoop.yarn.conf.YarnConfiguration.LOAD_BASED_SC_SELECTOR_FAIL_ON_ERROR;
import static org.apache.hadoop.yarn.conf.YarnConfiguration.DEFAULT_LOAD_BASED_SC_SELECTOR_FAIL_ON_ERROR;
import static org.apache.hadoop.yarn.conf.YarnConfiguration.FEDERATION_BLACKLIST_SUBCLUSTERS;
import static org.apache.hadoop.yarn.conf.YarnConfiguration.DEFAULT_FEDERATION_BLACKLIST_SUBCLUSTERS;

/**
 * An implementation of the {@link FederationAMRMProxyPolicy} interface that
 * carefully multicasts the requests with the following behavior:
 *
 * <p>
 * Host localized {@link ResourceRequest}s are always forwarded to the RM that
 * owns the corresponding node, based on the feedback of a
 * {@link SubClusterResolver}. If the {@link SubClusterResolver} cannot resolve
 * this node we default to forwarding the {@link ResourceRequest} to the home
 * sub-cluster.
 * </p>
 *
 * <p>
 * Rack localized {@link ResourceRequest}s are forwarded to the RMs that owns
 * the corresponding rack. Note that in some deployments each rack could be
 * striped across multiple RMs. This policy respects that. If the
 * {@link SubClusterResolver} cannot resolve this rack we default to forwarding
 * the {@link ResourceRequest} to the home sub-cluster.
 * </p>
 *
 * <p>
 * ANY requests corresponding to node/rack local requests are forwarded only to
 * the set of RMs that owns the corresponding localized requests. The number of
 * containers listed in each ANY is proportional to the number of localized
 * container requests (associated to this ANY via the same allocateRequestId).
 * </p>
 *
 * <p>
 * ANY that are not associated to node/rack local requests are split among RMs
 * based on the "weights" in the {@link WeightedPolicyInfo} configuration *and*
 * headroom information. The {@code headroomAlpha} parameter of the policy
 * configuration indicates how much headroom contributes to the splitting
 * choice. Value of 1.0f indicates the weights are interpreted only as 0/1
 * boolean but all splitting is based on the advertised headroom (fallback to
 * 1/N for RMs that we don't have headroom info from). An {@code headroomAlpha}
 * value of 0.0f means headroom is ignored and all splitting decisions are
 * proportional to the "weights" in the configuration of the policy.
 * </p>
 *
 * <p>
 * ANY of zero size are forwarded to all known subclusters (i.e., subclusters
 * where we scheduled containers before), as they may represent a user attempt
 * to cancel a previous request (and we are mostly stateless now, so should
 * forward to all known RMs).
 * </p>
 *
 * <p>
 * Invariants:
 * </p>
 *
 * <p>
 * The policy always excludes non-active RMs.
 * </p>
 *
 * <p>
 * The policy always excludes RMs that do not appear in the policy configuration
 * weights, or have a weight of 0 (even if localized resources explicit refer to
 * it).
 * </p>
 *
 * <p>
 * (Bar rounding to closest ceiling of fractional containers) The sum of
 * requests made to multiple RMs at the ANY level "adds-up" to the user request.
 * The maximum possible excess in a given request is a number of containers less
 * or equal to number of sub-clusters in the federation.
 * </p>
 */
public class LocalityMulticastAMRMProxyPolicy extends AbstractAMRMProxyPolicy {

  public static final Logger LOG =
      LoggerFactory.getLogger(LocalityMulticastAMRMProxyPolicy.class);

  private static Random rand = new Random();

  private Map<SubClusterId, Float> weights;
  private SubClusterResolver resolver;

  private Configuration conf;
  private Map<SubClusterId, Resource> headroom;
  private Map<SubClusterId, EnhancedHeadroom> enhancedHeadroom;
  private float hrAlpha;
  private FederationStateStoreFacade federationFacade;
  private SubClusterId homeSubcluster;
  private int printRRMax;
  public static final String PRINT_RR_MAX =
      "yarn.nodemanager.amrmproxy.address.splitmerge.printmaxrrcount";
  public static final int DEFAULT_PRINT_RR_MAX = 1000;
  private boolean failOnError = DEFAULT_LOAD_BASED_SC_SELECTOR_FAIL_ON_ERROR;

  /**
   * Print a list of Resource Requests into a one line string.
   *
   * @param response list of ResourceRequest
   * @param max number of ResourceRequest to print
   * @return the printed one line string
   */
  public static String prettyPrintRequests(List<ResourceRequest> response, int max) {
    StringBuilder builder = new StringBuilder();
    for (ResourceRequest rr : response) {
      builder.append("[id:").append(rr.getAllocationRequestId())
          .append(" loc:")
          .append(rr.getResourceName())
          .append(" num:")
          .append(rr.getNumContainers())
          .append(" pri:")
          .append(((rr.getPriority() != null) ? rr.getPriority().getPriority() : -1))
          .append("], ");
      if (max != -1) {
        if (max-- <= 0) {
          break;
        }
      }
    }
    return builder.toString();
  }

  @Override
  public void reinitialize(
      FederationPolicyInitializationContext policyContext)
      throws FederationPolicyInitializationException {

    // save reference to old weights
    WeightedPolicyInfo tempPolicy = getPolicyInfo();

    super.reinitialize(policyContext);
    if (!getIsDirty()) {
      return;
    }

    Map<SubClusterId, Float> newWeightsConverted = new HashMap<>();
    boolean allInactive = true;
    WeightedPolicyInfo policy = getPolicyInfo();

    if (policy.getAMRMPolicyWeights() != null
        && policy.getAMRMPolicyWeights().size() > 0) {
      for (Map.Entry<SubClusterIdInfo, Float> e : policy.getAMRMPolicyWeights()
          .entrySet()) {
        if (e.getValue() > 0) {
          allInactive = false;
        }
        newWeightsConverted.put(e.getKey().toId(), e.getValue());
      }
    }
    if (allInactive) {
      // reset the policyInfo and throw
      setPolicyInfo(tempPolicy);
      throw new FederationPolicyInitializationException(
          "The weights used to configure "
              + "this policy are all set to zero! (no ResourceRequest could be "
              + "forwarded with this setting.)");
    }

    if (policyContext.getHomeSubcluster() == null) {
      setPolicyInfo(tempPolicy);
      throw new FederationPolicyInitializationException("The homeSubcluster "
          + "filed in the context must be initialized to use this policy");
    }

    weights = newWeightsConverted;
    resolver = policyContext.getFederationSubclusterResolver();

    // Data structures that only need to initialize once
    if (headroom == null) {
      headroom = new ConcurrentHashMap<>();
      enhancedHeadroom = new ConcurrentHashMap<>();
    }
    hrAlpha = policy.getHeadroomAlpha();

    this.federationFacade =
        policyContext.getFederationStateStoreFacade();
    this.homeSubcluster = policyContext.getHomeSubcluster();

    this.conf = this.federationFacade.getConf();
    this.printRRMax = this.conf.getInt(PRINT_RR_MAX, DEFAULT_PRINT_RR_MAX);
    this.failOnError = this.conf.getBoolean(LOAD_BASED_SC_SELECTOR_FAIL_ON_ERROR,
        DEFAULT_LOAD_BASED_SC_SELECTOR_FAIL_ON_ERROR);
  }

  @Override
  public void notifyOfResponse(SubClusterId subClusterId,
      AllocateResponse response) throws YarnException {
    if (response.getAvailableResources() != null) {
      headroom.put(subClusterId, response.getAvailableResources());
    }
    if (response.getEnhancedHeadroom() != null) {
      this.enhancedHeadroom.put(subClusterId, response.getEnhancedHeadroom());
    }
    LOG.info(
        "Subcluster {} updated with AvailableResource {}, EnhancedHeadRoom {}",
        subClusterId, response.getAvailableResources(),
        response.getEnhancedHeadroom());
  }

  @Override
  public Map<SubClusterId, List<ResourceRequest>> splitResourceRequests(
      List<ResourceRequest> resourceRequests,
      Set<SubClusterId> timedOutSubClusters) throws YarnException {

    // object used to accumulate statistics about the answer, initialize with
    // active subclusters. Create a new instance per call because this method
    // can be called concurrently.
    AllocationBookkeeper bookkeeper = new AllocationBookkeeper();
    bookkeeper.reinitialize(getActiveSubclusters(), timedOutSubClusters, conf);

    List<ResourceRequest> nonLocalizedRequests = new ArrayList<>();

    SubClusterId targetId = null;
    Set<SubClusterId> targetIds = null;

    // if the RR is resolved to a local subcluster add it directly (node and
    // resolvable racks)
    for (ResourceRequest rr : resourceRequests) {
      targetId = null;
      targetIds = null;

      // Handle: ANY (accumulated for later)
      if (ResourceRequest.isAnyLocation(rr.getResourceName())) {
        nonLocalizedRequests.add(rr);
        continue;
      }

      // Handle "node" requests
      try {
        targetId = resolver.getSubClusterForNode(rr.getResourceName());

        // If needed, re-reroute node requests base on SC load
        boolean loadBasedSCSelectorEnabled =
            conf.getBoolean(LOAD_BASED_SC_SELECTOR_ENABLED, DEFAULT_LOAD_BASED_SC_SELECTOR_ENABLED);
        if (loadBasedSCSelectorEnabled) {
          int maxPendingThreshold = conf.getInt(LOAD_BASED_SC_SELECTOR_THRESHOLD,
              DEFAULT_LOAD_BASED_SC_SELECTOR_THRESHOLD);
          targetId = routeNodeRequestIfNeeded(targetId, maxPendingThreshold,
              bookkeeper.getActiveAndEnabledSC());
        }
        LOG.debug("Node request {}", rr.getResourceName());
      } catch (YarnException e) {
        // this might happen as we can't differentiate node from rack names
        // we log altogether later
      }
      if (bookkeeper.isActiveAndEnabled(targetId)) {
        bookkeeper.addLocalizedNodeRR(targetId, rr);
        continue;
      }

      // Handle "rack" requests
      try {
        targetIds = resolver.getSubClustersForRack(rr.getResourceName());
      } catch (YarnException e) {
        // this might happen as we can't differentiate node from rack names
        // we log altogether later
      }
      if (targetIds != null && targetIds.size() > 0) {
        boolean hasActive = false;
        for (SubClusterId tid : targetIds) {
          if (bookkeeper.isActiveAndEnabled(tid)) {
            bookkeeper.addRackRR(tid, rr);
            hasActive = true;
          }
        }
        if (hasActive) {
          continue;
        }
      }

      // Handle node/rack requests that the SubClusterResolver cannot map to
      // any cluster. Pick a random sub-cluster from active and enabled ones.
      targetId = getSubClusterForUnResolvedRequest(bookkeeper,
          rr.getAllocationRequestId());
      LOG.debug("ERROR resolving sub-cluster for resourceName: {}, picked a "
          + "random subcluster to forward:{}", rr.getResourceName(), targetId);
      if (targetIds != null && targetIds.size() > 0) {
        bookkeeper.addRackRR(targetId, rr);
      } else {
        bookkeeper.addLocalizedNodeRR(targetId, rr);
      }
    }

    // handle all non-localized requests (ANY)
    splitAnyRequests(nonLocalizedRequests, bookkeeper);

    // Take the split result, feed into the askBalancer
    Map<SubClusterId, List<ResourceRequest>> answer = bookkeeper.getAnswer();
    LOG.info("Before split {} RRs: {}", resourceRequests.size(),
        prettyPrintRequests(resourceRequests, this.printRRMax));

    for (Map.Entry<SubClusterId, List<ResourceRequest>> entry : bookkeeper.getAnswer().entrySet()) {
      LOG.info("After split {} has {} RRs: {}", entry.getKey(), entry.getValue().size(),
          prettyPrintRequests(entry.getValue(), this.printRRMax));
    }
    return answer;
  }

  /**
   * For unit test to override.
   *
   * @param bookKeeper bookKeeper
   * @param allocationId allocationId.
   * @return SubClusterId.
   */
  protected SubClusterId getSubClusterForUnResolvedRequest(
      AllocationBookkeeper bookKeeper, long allocationId) {
    return bookKeeper.getSubClusterForUnResolvedRequest(allocationId);
  }

  /**
   * It splits a list of non-localized resource requests among sub-clusters.
   */
  private void splitAnyRequests(List<ResourceRequest> originalResourceRequests,
      AllocationBookkeeper allocationBookkeeper) throws YarnException {

    for (ResourceRequest resourceRequest : originalResourceRequests) {

      // FIRST: pick the target set of subclusters (based on whether this RR
      // is associated with other localized requests via an allocationId)
      Long allocationId = resourceRequest.getAllocationRequestId();
      Set<SubClusterId> targetSubclusters;
      if (allocationBookkeeper.getSubClustersForId(allocationId) != null) {
        targetSubclusters =
            allocationBookkeeper.getSubClustersForId(allocationId);
      } else {
        targetSubclusters = allocationBookkeeper.getActiveAndEnabledSC();
      }

      // SECOND: pick how much to ask to each RM for each request
      splitIndividualAny(resourceRequest, targetSubclusters,
          allocationBookkeeper);
    }
  }

  /**
   * Return a projection of this ANY {@link ResourceRequest} that belongs to
   * this sub-cluster. This is done based on the "count" of the containers that
   * require locality in each sublcuster (if any) or based on the "weights" and
   * headroom.
   */
  private void splitIndividualAny(ResourceRequest originalResourceRequest,
      Set<SubClusterId> targetSubclusters,
      AllocationBookkeeper allocationBookkeeper) throws YarnException {

    long allocationId = originalResourceRequest.getAllocationRequestId();
    int numContainer = originalResourceRequest.getNumContainers();

    // If the ANY request has 0 containers to begin with we must forward it to
    // any RM we have previously contacted (this might be the user way
    // to cancel a previous request).
    if (numContainer == 0) {
      for (SubClusterId targetId : headroom.keySet()) {
        allocationBookkeeper.addAnyRR(targetId, originalResourceRequest);
      }
      return;
    }

    // List preserves iteration order
    List<SubClusterId> targetSCs = new ArrayList<>(targetSubclusters);

    // Compute the distribution weights
    ArrayList<Float> weightsList = new ArrayList<>();
    for (SubClusterId targetId : targetSCs) {
      // If ANY is associated with localized asks, split based on their ratio
      if (allocationBookkeeper.getSubClustersForId(allocationId) != null) {
        weightsList.add(getLocalityBasedWeighting(allocationId, targetId,
            allocationBookkeeper));
      } else {
        // split ANY based on load and policy configuration
        float headroomWeighting =
            getHeadroomWeighting(targetId, allocationBookkeeper);
        float policyWeighting =
            getPolicyConfigWeighting(targetId, allocationBookkeeper);
        // hrAlpha controls how much headroom influencing decision
        weightsList
            .add(hrAlpha * headroomWeighting + (1 - hrAlpha) * policyWeighting);
      }
    }

    // Compute the integer container counts for each sub-cluster
    ArrayList<Integer> containerNums =
        computeIntegerAssignment(numContainer, weightsList);
    int i = 0;
    for (SubClusterId targetId : targetSCs) {
      // if the calculated request is non-empty add it to the answer
      if (containerNums.get(i) > 0) {
        ResourceRequest out = ResourceRequest.clone(originalResourceRequest);
        out.setNumContainers(containerNums.get(i));
        if (ResourceRequest.isAnyLocation(out.getResourceName())) {
          allocationBookkeeper.addAnyRR(targetId, out);
        } else {
          allocationBookkeeper.addRackRR(targetId, out);
        }
      }
      i++;
    }
  }

  /**
   * Split the integer into bins according to the weights.
   *
   * @param totalNum total number of containers to split
   * @param weightsList the weights for each subcluster
   * @return the container allocation after split
   * @throws YarnException if fails
   */
  @VisibleForTesting
  protected ArrayList<Integer> computeIntegerAssignment(int totalNum,
      ArrayList<Float> weightsList) throws YarnException {
    int i, residue;
    ArrayList<Integer> ret = new ArrayList<>();
    float totalWeight = 0, totalNumFloat = totalNum;

    if (weightsList.size() == 0) {
      return ret;
    }
    for (i = 0; i < weightsList.size(); i++) {
      ret.add(0);
      if (weightsList.get(i) > 0) {
        totalWeight += weightsList.get(i);
      }
    }
    if (totalWeight == 0) {
      StringBuilder sb = new StringBuilder();
      for (Float weight : weightsList) {
        sb.append(weight + ", ");
      }
      throw new FederationPolicyException(
          "No positive value found in weight array " + sb.toString());
    }

    // First pass, do flooring for all bins
    residue = totalNum;
    for (i = 0; i < weightsList.size(); i++) {
      if (weightsList.get(i) > 0) {
        int base = (int) (totalNumFloat * weightsList.get(i) / totalWeight);
        ret.set(i, ret.get(i) + base);
        residue -= base;
      }
    }

    // By now residue < weights.length, assign one a time
    for (i = 0; i < residue; i++) {
      int index = FederationPolicyUtils.getWeightedRandom(weightsList);
      ret.set(index, ret.get(index) + 1);
    }
    return ret;
  }

  /**
   * Compute the weight to assign to a subcluster based on how many local
   * requests a subcluster is target of.
   */
  private float getLocalityBasedWeighting(long reqId, SubClusterId targetId,
      AllocationBookkeeper allocationBookkeeper) {
    float totWeight = allocationBookkeeper.getTotNumLocalizedContainers(reqId);
    float localWeight =
        allocationBookkeeper.getNumLocalizedContainers(reqId, targetId);
    return totWeight > 0 ? localWeight / totWeight : 0;
  }

  /**
   * Compute the "weighting" to give to a sublcuster based on the configured
   * policy weights (for the active subclusters).
   */
  private float getPolicyConfigWeighting(SubClusterId targetId,
      AllocationBookkeeper allocationBookkeeper) {
    float totWeight = allocationBookkeeper.totPolicyWeight;
    Float localWeight = allocationBookkeeper.policyWeights.get(targetId);
    return (localWeight != null && totWeight > 0) ? localWeight / totWeight : 0;
  }

  /**
   * Compute the weighting based on available headroom. This is proportional to
   * the available headroom memory announced by RM, or to 1/N for RMs we have
   * not seen yet. If all RMs report zero headroom, we fallback to 1/N again.
   */
  private float getHeadroomWeighting(SubClusterId targetId,
      AllocationBookkeeper allocationBookkeeper) {

    // baseline weight for all RMs
    float headroomWeighting =
        1 / (float) allocationBookkeeper.getActiveAndEnabledSC().size();

    // if we have headroom information for this sub-cluster (and we are safe
    // from /0 issues)
    if (headroom.containsKey(targetId)
        && allocationBookkeeper.totHeadroomMemory > 0) {
      // compute which portion of the RMs that are active/enabled have reported
      // their headroom (needed as adjustment factor)
      // (note: getActiveAndEnabledSC should never be null/zero)
      float ratioHeadroomKnown = allocationBookkeeper.totHeadRoomEnabledRMs
          / (float) allocationBookkeeper.getActiveAndEnabledSC().size();

      // headroomWeighting is the ratio of headroom memory in the targetId
      // cluster / total memory. The ratioHeadroomKnown factor is applied to
      // adjust for missing information and ensure sum of allocated containers
      // closely approximate what the user asked (small excess).
      headroomWeighting = (headroom.get(targetId).getMemorySize()
          / allocationBookkeeper.totHeadroomMemory) * (ratioHeadroomKnown);
    }
    return headroomWeighting;
  }

  /**
   * When certain subcluster is too loaded, reroute Node requests going there.
   *
   * @param targetId current subClusterId where request is sent
   * @param maxThreshold threshold for Pending count
   * @param activeAndEnabledSCs list of active sc
   * @return subClusterId target sc id
   */
  protected SubClusterId routeNodeRequestIfNeeded(SubClusterId targetId,
      int maxThreshold, Set<SubClusterId> activeAndEnabledSCs) {
    // If targetId is not in the active and enabled SC list, reroute the traffic
    if (activeAndEnabledSCs.contains(targetId)) {
      int targetPendingCount = getSubClusterLoad(targetId);
      if (targetPendingCount == -1 || targetPendingCount < maxThreshold) {
        return targetId;
      }
    }
    SubClusterId scId = chooseSubClusterIdForMaxLoadSC(targetId, maxThreshold, activeAndEnabledSCs);
    return scId;
  }

  /**
   * Check if the current target subcluster is over max load, and if it is
   * reroute it.
   *
   * @param targetId            the original target subcluster id
   * @param maxThreshold        the max load threshold to reroute
   * @param activeAndEnabledSCs the list of active and enabled subclusters
   * @return targetId if it is within maxThreshold, otherwise a new id
   */
  private SubClusterId chooseSubClusterIdForMaxLoadSC(SubClusterId targetId,
      int maxThreshold, Set<SubClusterId> activeAndEnabledSCs) {
    ArrayList<Float> weight = new ArrayList<>();
    ArrayList<SubClusterId> scIds = new ArrayList<>();
    int targetLoad = getSubClusterLoad(targetId);
    if (targetLoad == -1 || !activeAndEnabledSCs.contains(targetId)) {
      // Probably a SC that's not active and enabled. Forcing a reroute
      targetLoad = Integer.MAX_VALUE;
    }

    /*
     * Prepare the weight for a random draw among all known SCs.
     *
     * For SC with pending bigger than maxThreshold / 2, use maxThreshold /
     * pending as weight. We multiplied by maxThreshold so that the weight
     * won't be too small in value.
     *
     * For SC with pending less than maxThreshold / 2, we cap the weight at 2
     * = (maxThreshold / (maxThreshold / 2)) so that SC with small pending
     * will not get a huge weight and thus get swamped.
     */
    for (SubClusterId sc : activeAndEnabledSCs) {
      int scLoad = getSubClusterLoad(sc);
      if (scLoad > targetLoad) {
        // Never mind if it is not the most loaded SC
        return targetId;
      }
      if (scLoad <= maxThreshold / 2) {
        weight.add(2f);
      } else {
        weight.add((float) maxThreshold / scLoad);
      }
      scIds.add(sc);
    }
    if (weights.size() == 0) {
      return targetId;
    }
    return scIds.get(FederationPolicyUtils.getWeightedRandom(weight));
  }

  /**
   * get the Load data of the subCluster.
   *
   * @param subClusterId subClusterId.
   * @return The number of pending containers for the subCluster.
   */
  private int getSubClusterLoad(SubClusterId subClusterId) {
    EnhancedHeadroom headroomData = this.enhancedHeadroom.get(subClusterId);
    if (headroomData == null) {
      return -1;
    }

    // Use new data from enhanced headroom
    boolean useActiveCoreEnabled = conf.getBoolean(LOAD_BASED_SC_SELECTOR_USE_ACTIVE_CORE,
        DEFAULT_LOAD_BASED_SC_SELECTOR_USE_ACTIVE_CORE);

    // If we consider the number of vCores in the subCluster
    if (useActiveCoreEnabled) {

      // If the vcore of the subCluster is less than or equal to 0,
      // it means that containers cannot be scheduled to this subCluster,
      // and we will return a very large number, indicating that the subCluster is unavailable.
      if (headroomData.getTotalActiveCores() <= 0) {
        return Integer.MAX_VALUE;
      }

      // Multiply by a constant factor, to ensure the numerator > denominator.
      // We will normalize the PendingCount, using PendingCount * multiplier / TotalActiveCores.
      long multiplier = conf.getLong(LOAD_BASED_SC_SELECTOR_MULTIPLIER,
          DEFAULT_LOAD_BASED_SC_SELECTOR_MULTIPLIER);
      double value =
          headroomData.getNormalizedPendingCount(multiplier) / headroomData.getTotalActiveCores();
      if (value > Integer.MAX_VALUE) {
        return Integer.MAX_VALUE;
      } else {
        return (int) value;
      }
    } else {
      // If the number of vcores in the subCluster is not considered,
      // we directly return the number of pending containers in the subCluster.
      return headroomData.getTotalPendingCount();
    }
  }

  /**
   * This helper class is used to book-keep the requests made to each
   * subcluster, and maintain useful statistics to split ANY requests.
   */
  protected final class AllocationBookkeeper {

    // the answer being accumulated
    private Map<SubClusterId, List<ResourceRequest>> answer = new TreeMap<>();
    private Map<SubClusterId, Set<Long>> maskForRackDeletion = new HashMap<>();

    // stores how many containers we have allocated in each RM for localized
    // asks, used to correctly "spread" the corresponding ANY
    private Map<Long, Map<SubClusterId, AtomicLong>> countContainersPerRM =
        new HashMap<>();
    private Map<Long, AtomicLong> totNumLocalizedContainers = new HashMap<>();

    // Store the randomly selected subClusterId for unresolved resource requests
    // keyed by requestId
    private Map<Long, SubClusterId> unResolvedRequestLocation = new HashMap<>();

    private Set<SubClusterId> activeAndEnabledSC = new HashSet<>();
    private float totHeadroomMemory = 0;
    private int totHeadRoomEnabledRMs = 0;
    private Map<SubClusterId, Float> policyWeights;
    private float totPolicyWeight = 0;

    private void reinitialize(
        Map<SubClusterId, SubClusterInfo> activeSubclusters,
        Set<SubClusterId> timedOutSubClusters, Configuration pConf) throws YarnException {

      if (MapUtils.isEmpty(activeSubclusters)) {
        throw new YarnRuntimeException("null activeSubclusters received");
      }

      // reset data structures
      answer.clear();
      maskForRackDeletion.clear();
      countContainersPerRM.clear();
      totNumLocalizedContainers.clear();
      activeAndEnabledSC.clear();
      totHeadroomMemory = 0;
      totHeadRoomEnabledRMs = 0;
      // save the reference locally in case the weights get reinitialized
      // concurrently
      policyWeights = weights;
      totPolicyWeight = 0;

      for (Map.Entry<SubClusterId, Float> entry : policyWeights.entrySet()) {
        if (entry.getValue() > 0
            && activeSubclusters.containsKey(entry.getKey())) {
          activeAndEnabledSC.add(entry.getKey());
        }
      }

      // subCluster blacklisting from configuration
      String blacklistedSubClusters = pConf.get(FEDERATION_BLACKLIST_SUBCLUSTERS,
          DEFAULT_FEDERATION_BLACKLIST_SUBCLUSTERS);
      if (blacklistedSubClusters != null) {
        Collection<String> tempList = StringUtils.getStringCollection(blacklistedSubClusters);
        for (String item : tempList) {
          activeAndEnabledSC.remove(SubClusterId.newInstance(item.trim()));
        }
      }

      if (activeAndEnabledSC.size() < 1) {
        String errorMsg = "None of the subClusters enabled in this Policy (weight > 0) are "
            + "currently active we cannot forward the ResourceRequest(s)";
        if (failOnError) {
          throw new NoActiveSubclustersException(errorMsg);
        } else {
          LOG.error(errorMsg + ", continuing by enabling all active subClusters.");
          activeAndEnabledSC.addAll(activeSubclusters.keySet());
          for (SubClusterId sc : activeSubclusters.keySet()) {
            policyWeights.put(sc, 1.0f);
          }
        }
      }

      Set<SubClusterId> tmpSCSet = new HashSet<>(activeAndEnabledSC);
      tmpSCSet.removeAll(timedOutSubClusters);

      if (tmpSCSet.size() < 1) {
        LOG.warn("All active and enabled subclusters have expired last "
            + "heartbeat time. Ignore the expiry check for this request.");
      } else {
        activeAndEnabledSC = tmpSCSet;
      }

      LOG.info("{} subcluster active, {} subclusters active and enabled",
          activeSubclusters.size(), activeAndEnabledSC.size());

      // pre-compute the set of subclusters that are both active and enabled by
      // the policy weights, and accumulate their total weight
      for (SubClusterId sc : activeAndEnabledSC) {
        totPolicyWeight += policyWeights.get(sc);
      }

      // pre-compute headroom-based weights for active/enabled subclusters
      for (Map.Entry<SubClusterId, Resource> r : headroom.entrySet()) {
        if (activeAndEnabledSC.contains(r.getKey())) {
          totHeadroomMemory += r.getValue().getMemorySize();
          totHeadRoomEnabledRMs++;
        }
      }
    }

    /**
     * Add to the answer a localized node request, and keeps track of statistics
     * on a per-allocation-id and per-subcluster bases.
     */
    private void addLocalizedNodeRR(SubClusterId targetId, ResourceRequest rr) {
      Preconditions
          .checkArgument(!ResourceRequest.isAnyLocation(rr.getResourceName()));

      if (rr.getNumContainers() > 0) {
        if (!countContainersPerRM.containsKey(rr.getAllocationRequestId())) {
          countContainersPerRM.put(rr.getAllocationRequestId(),
              new HashMap<>());
        }
        if (!countContainersPerRM.get(rr.getAllocationRequestId())
            .containsKey(targetId)) {
          countContainersPerRM.get(rr.getAllocationRequestId()).put(targetId,
              new AtomicLong(0));
        }
        countContainersPerRM.get(rr.getAllocationRequestId()).get(targetId)
            .addAndGet(rr.getNumContainers());

        if (!totNumLocalizedContainers
            .containsKey(rr.getAllocationRequestId())) {
          totNumLocalizedContainers.put(rr.getAllocationRequestId(),
              new AtomicLong(0));
        }
        totNumLocalizedContainers.get(rr.getAllocationRequestId())
            .addAndGet(rr.getNumContainers());
      }

      internalAddToAnswer(targetId, rr, false);
    }

    /**
     * Add a rack-local request to the final answer.
     */
    private void addRackRR(SubClusterId targetId, ResourceRequest rr) {
      Preconditions
          .checkArgument(!ResourceRequest.isAnyLocation(rr.getResourceName()));
      internalAddToAnswer(targetId, rr, true);
    }

    /**
     * Add an ANY request to the final answer.
     */
    private void addAnyRR(SubClusterId targetId, ResourceRequest rr) {
      Preconditions
          .checkArgument(ResourceRequest.isAnyLocation(rr.getResourceName()));
      internalAddToAnswer(targetId, rr, false);
    }

    private void internalAddToAnswer(SubClusterId targetId,
        ResourceRequest partialRR, boolean isRack) {
      if (!isRack) {
        if (!maskForRackDeletion.containsKey(targetId)) {
          maskForRackDeletion.put(targetId, new HashSet<Long>());
        }
        maskForRackDeletion.get(targetId)
            .add(partialRR.getAllocationRequestId());
      }
      if (!answer.containsKey(targetId)) {
        answer.put(targetId, new ArrayList<ResourceRequest>());
      }
      answer.get(targetId).add(partialRR);
    }

    /**
     * For requests whose location cannot be resolved, choose an active and
     * enabled sub-cluster to forward this requestId to.
     */
    private SubClusterId getSubClusterForUnResolvedRequest(long allocationId) {
      if (unResolvedRequestLocation.containsKey(allocationId)) {
        return unResolvedRequestLocation.get(allocationId);
      }
      int id = rand.nextInt(activeAndEnabledSC.size());
      for (SubClusterId subclusterId : activeAndEnabledSC) {
        if (id == 0) {
          unResolvedRequestLocation.put(allocationId, subclusterId);
          return subclusterId;
        }
        id--;
      }
      throw new RuntimeException(
          "Should not be here. activeAndEnabledSC size = "
              + activeAndEnabledSC.size() + " id = " + id);
    }

    /**
     * Return all known subclusters associated with an allocation id.
     *
     * @param allocationId the allocation id considered
     *
     * @return the list of {@link SubClusterId}s associated with this allocation
     *         id
     */
    private Set<SubClusterId> getSubClustersForId(long allocationId) {
      if (countContainersPerRM.get(allocationId) == null) {
        return null;
      }
      return countContainersPerRM.get(allocationId).keySet();
    }

    /**
     * Return the answer accumulated so far.
     *
     * @return the answer
     */
    private Map<SubClusterId, List<ResourceRequest>> getAnswer() {
      Iterator<Entry<SubClusterId, List<ResourceRequest>>> answerIter =
          answer.entrySet().iterator();
      // Remove redundant rack RR before returning the answer
      while (answerIter.hasNext()) {
        Entry<SubClusterId, List<ResourceRequest>> entry = answerIter.next();
        SubClusterId scId = entry.getKey();
        Set<Long> mask = maskForRackDeletion.get(scId);
        if (mask != null) {
          Iterator<ResourceRequest> rrIter = entry.getValue().iterator();
          while (rrIter.hasNext()) {
            ResourceRequest rr = rrIter.next();
            if (!mask.contains(rr.getAllocationRequestId())) {
              rrIter.remove();
            }
          }
        }
        if (mask == null || entry.getValue().size() == 0) {
          answerIter.remove();
          LOG.info("removing {} from output because it has only rack RR",
              scId);
        }
      }
      return answer;
    }

    /**
     * Return the set of sub-clusters that are both active and allowed by our
     * policy (weight > 0).
     *
     * @return a set of active and enabled {@link SubClusterId}s
     */
    private Set<SubClusterId> getActiveAndEnabledSC() {
      return activeAndEnabledSC;
    }

    /**
     * Return the total number of container coming from localized requests
     * matching an allocation Id.
     */
    private long getTotNumLocalizedContainers(long allocationId) {
      AtomicLong c = totNumLocalizedContainers.get(allocationId);
      return c == null ? 0 : c.get();
    }

    /**
     * Returns the number of containers matching an allocation Id that are
     * localized in the targetId subcluster.
     */
    private long getNumLocalizedContainers(long allocationId,
        SubClusterId targetId) {
      AtomicLong c = countContainersPerRM.get(allocationId).get(targetId);
      return c == null ? 0 : c.get();
    }

    /**
     * Returns true is the subcluster request is both active and enabled.
     */
    private boolean isActiveAndEnabled(SubClusterId targetId) {
      if (targetId == null) {
        return false;
      } else {
        return getActiveAndEnabledSC().contains(targetId);
      }
    }

  }
}