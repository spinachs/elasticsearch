/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.ccr;

import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.node.Node;
import org.elasticsearch.xpack.CcrIntegTestCase;
import org.elasticsearch.xpack.core.ccr.action.PutFollowAction;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.in;

public class PrimaryFollowerAllocationIT extends CcrIntegTestCase {

    @Override
    protected boolean reuseClusters() {
        return false;
    }

    public void testAllocateFollowerPrimaryToNodesWithRemoteClusterClientRole() throws Exception {
        removeMasterNodeRequestsValidatorOnFollowerCluster();
        final String leaderIndex = "leader-allow-index";
        final String followerIndex = "follower-allow-index";
        final List<String> dataOnlyNodes = getFollowerCluster().startNodes(between(2, 3),
            Settings.builder().put(Node.NODE_DATA_SETTING.getKey(), true).build());
        final List<String> dataAndRemoteNodes = getFollowerCluster().startNodes(between(1, 2),
            Settings.builder().put(Node.NODE_DATA_SETTING.getKey(), true).put(Node.NODE_REMOTE_CLUSTER_CLIENT.getKey(), true).build());
        assertAcked(leaderClient().admin().indices().prepareCreate(leaderIndex)
            .setSource(getIndexSettings(between(1, 2), between(0, 1)), XContentType.JSON));
        final PutFollowAction.Request putFollowRequest = putFollow(leaderIndex, followerIndex);
        final PutFollowAction.Response response = followerClient().execute(PutFollowAction.INSTANCE, putFollowRequest).get();
        assertTrue(response.isFollowIndexShardsAcked());
        assertTrue(response.isIndexFollowingStarted());
        ensureFollowerGreen(followerIndex);
        int numDocs = between(0, 20);
        for (int i = 0; i < numDocs; i++) {
            leaderClient().prepareIndex(leaderIndex, "_doc").setSource("f", i).get();
        }
        // Follower primaries can be relocated to nodes without the remote cluster client role
        followerClient().admin().indices().prepareUpdateSettings(followerIndex)
            .setMasterNodeTimeout(TimeValue.MAX_VALUE)
            .setSettings(Settings.builder().put("index.routing.allocation.include._name", String.join(",", dataOnlyNodes)))
            .get();
        assertBusy(() -> {
            final ClusterState state = getFollowerCluster().client().admin().cluster().prepareState().get().getState();
            for (IndexShardRoutingTable shardRoutingTable : state.routingTable().index(followerIndex)) {
                for (ShardRouting shard : shardRoutingTable) {
                    assertNotNull(shard.currentNodeId());
                    final DiscoveryNode assignedNode = state.nodes().get(shard.currentNodeId());
                    assertThat(shardRoutingTable.toString(), assignedNode.getName(), in(dataOnlyNodes));
                }
            }
        }, 30, TimeUnit.SECONDS);
        assertIndexFullyReplicatedToFollower(leaderIndex, followerIndex);
        // Follower primaries can be recovered from the existing copies on nodes without the remote cluster client role
        getFollowerCluster().fullRestart();
        ensureFollowerGreen(followerIndex);
        assertBusy(() -> {
            final ClusterState state = getFollowerCluster().client().admin().cluster().prepareState().get().getState();
            for (IndexShardRoutingTable shardRoutingTable : state.routingTable().index(followerIndex)) {
                for (ShardRouting shard : shardRoutingTable) {
                    assertNotNull(shard.currentNodeId());
                    final DiscoveryNode assignedNode = state.nodes().get(shard.currentNodeId());
                    assertThat(shardRoutingTable.toString(), assignedNode.getName(), in(dataOnlyNodes));
                }
            }
        }, 30, TimeUnit.SECONDS);
        int moreDocs = between(0, 20);
        for (int i = 0; i < moreDocs; i++) {
            leaderClient().prepareIndex(leaderIndex, "_doc").setSource("f", i).get();
        }
        assertIndexFullyReplicatedToFollower(leaderIndex, followerIndex);
    }
}
