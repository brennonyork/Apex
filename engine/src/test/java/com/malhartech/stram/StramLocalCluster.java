/**
 * Copyright (c) 2012-2012 Malhar, Inc.
 * All rights reserved.
 */
package com.malhartech.stram;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.ipc.ProtocolSignature;
import org.apache.hadoop.net.NetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.malhartech.bufferserver.Server;
import com.malhartech.stram.StramChildAgent.DeployRequest;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.ContainerHeartbeatResponse;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.StreamingContainerContext;
import com.malhartech.stram.conf.TopologyBuilder;

/**
 * Launcher for topologies in local mode within a single process.
 * Child containers are mapped to threads.
 */
public class StramLocalCluster implements Runnable {

  private static Logger LOG = LoggerFactory.getLogger(StramLocalCluster.class);
  // assumes execution as unit test
  private static File CLUSTER_WORK_DIR = new File("target", StramLocalCluster.class.getName());

  final private DNodeManager dnmgr;
  final private UmbilicalProtocolLocalImpl umbilical;
  final private InetSocketAddress bufferServerAddress;
  private Server bufferServer = null;
  final private Map<String, StramChild> childContainers = new ConcurrentHashMap<String, StramChild>();
  private int containerSeq = 0;
  
  private class UmbilicalProtocolLocalImpl implements StreamingNodeUmbilicalProtocol {

    @Override
    public long getProtocolVersion(String protocol, long clientVersion)
        throws IOException {
      throw new UnsupportedOperationException("not implemented in local mode");
    }

    @Override
    public ProtocolSignature getProtocolSignature(String protocol,
        long clientVersion, int clientMethodsHash) throws IOException {
      throw new UnsupportedOperationException("not implemented in local mode");
    }
    
    @Override
    public void log(String containerId, String msg) throws IOException {
      LOG.info("child msg: {} context: {}", msg, dnmgr.getContainerAgent(containerId).container);
    }

    @Override
    public StreamingContainerContext getInitContext(String containerId)
        throws IOException {
      StramChildAgent sca = dnmgr.getContainerAgent(containerId);
      return sca.getInitContext();
    }

    @Override
    public ContainerHeartbeatResponse processHeartbeat(ContainerHeartbeat msg) {
      return dnmgr.processHeartbeat(msg);
    }

    @Override
    public ContainerHeartbeatResponse pollRequest(String containerId) {
      StramChildAgent sca = dnmgr.getContainerAgent(containerId);
      return sca.pollRequest();
    }

    @Override
    public StramToNodeRequest processPartioningDetails() {
      throw new RuntimeException("processPartioningDetails not implemented");
    }
    
  }
  
  public static class LocalStramChild extends StramChild
  {
    public LocalStramChild(String containerId, StreamingNodeUmbilicalProtocol umbilical)
    {
      super(containerId, new Configuration(), umbilical);
    }

    @Override
    public void init(StreamingContainerContext ctx) throws IOException
    {
      super.init(ctx);
    }

    @Override
    public void shutdown()
    {
      super.shutdown();
    }

    public static void run(StramChild stramChild, StreamingContainerContext ctx) throws Exception {
      LOG.debug("Got context: " + ctx);
      stramChild.init(ctx);
      // main thread enters heartbeat loop
      stramChild.heartbeatLoop();
      // shutdown
      stramChild.shutdown();
    }
    
  }
  
  /**
   * Starts the child "container" as thread.
   */
  private class LocalStramChildLauncher implements Runnable {
    final String containerId;
    final StramChild child; 
    
    private LocalStramChildLauncher(DeployRequest cdr) {
      this.containerId = "container-" + containerSeq++;
      this.child = new LocalStramChild(containerId, umbilical);
      dnmgr.assignContainer(cdr, containerId, NetUtils.getConnectAddress(bufferServerAddress));
      Thread launchThread = new Thread(this, containerId);
      launchThread.start();
      childContainers.put(containerId, child);
      LOG.info("Started container {}", containerId);
    }
    
    @Override
    public void run() {
      try {
        StreamingContainerContext ctx = umbilical.getInitContext(containerId);
        LocalStramChild.run(child, ctx);
      } catch (Exception e) {
        LOG.error("Container {} failed", containerId, e);
        throw new RuntimeException(e);
      } finally {
        childContainers.remove(containerId);
        LOG.info("Container {} terminating.", containerId);
      }
    }
  }

  public StramLocalCluster(TopologyBuilder topology) {

    try {
      FileContext.getLocalFSFileContext().delete(
          new Path(CLUSTER_WORK_DIR.getAbsolutePath()), true);
    } catch (Exception e) {
      throw new RuntimeException("could not cleanup test dir", e);
    }     
    
    if (topology.getConf().get(TopologyBuilder.STRAM_CHECKPOINT_DIR) == null) {
      topology.getConf().set(TopologyBuilder.STRAM_CHECKPOINT_DIR, CLUSTER_WORK_DIR.getPath());
    }
    this.dnmgr = new DNodeManager(topology);
    this.umbilical = new UmbilicalProtocolLocalImpl();

    // start buffer server
    this.bufferServer = new Server(0);
    SocketAddress bindAddr = this.bufferServer.run();
    this.bufferServerAddress = ((InetSocketAddress) bindAddr);
    LOG.info("Buffer server started: {}", bufferServerAddress);
  }

  boolean appDone = false;

  StramChild getContainer(int containerSeq) {
    return this.childContainers.get("container-" + containerSeq);
  }
  
  public void runAsync() {
    new Thread(this, "master").start();
  }
  
  @Override
  public void run() {
    while (!appDone) {
      try {
        Thread.sleep(1000);
      }
      catch (InterruptedException e) {
        LOG.info("Sleep interrupted " + e.getMessage());
      }

      for (String containerIdStr : dnmgr.containerStopRequests.values()) {
        // shutdown child thread
        StramChild c = childContainers.get(containerIdStr);
        if (c != null) {
          ContainerHeartbeatResponse r = new ContainerHeartbeatResponse();
          r.setShutdown(true);
          c.processHeartbeatResponse(r);
        }
        dnmgr.containerStopRequests.remove(containerIdStr);
      }
      
      // start containers
      while (!dnmgr.containerStartRequests.isEmpty()) {
        DeployRequest cdr = dnmgr.containerStartRequests.poll();
        if (cdr != null) {
          new LocalStramChildLauncher(cdr);
        }      
      }
      
      // monitor child containers
      dnmgr.monitorHeartbeat();

      if (childContainers.size() == 0 && dnmgr.containerStartRequests.isEmpty()) {
        appDone = true;
      }
    }
    
    LOG.info("Application finished.");
    bufferServer.shutdown();
  }
  
}