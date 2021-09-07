package io.camunda.zeebe.gateway.health;

import io.camunda.zeebe.gateway.protocol.GatewayGrpc;
import io.grpc.BindableService;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.HealthStatusManager;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
public final class GatewayHealthManager {
  private static final Set<String> MONITORED_SERVICES =
      Set.of(GatewayGrpc.SERVICE_NAME, HealthStatusManager.SERVICE_NAME_ALL_SERVICES);

  private final HealthStatusManager statusManager;
  private final AtomicReference<Status> status = new AtomicReference<>();

  public GatewayHealthManager() {
    this(new HealthStatusManager());
  }

  public GatewayHealthManager(final HealthStatusManager statusManager) {
    this.statusManager = statusManager;
    setStatus(Status.INITIAL);
  }

  /**
   * This method returns the latest health status for the gateway, and is thread safe.
   *
   * @return the current health status
   */
  public Status getStatus() {
    return status.get();
  }

  /**
   * This method sets the health status in a thread safe way.
   *
   * @param status the new health status of the gateway
   */
  public void setStatus(final Status status) {
    final var oldStatus = this.status.getAndAccumulate(status, this::computeStatus);

    if (oldStatus != Status.SHUTDOWN && oldStatus != status) {
      updateGrpcHealthStatus(status);
    }
  }

  private Status computeStatus(final Status currentStatus, final Status newStatus) {
    if (currentStatus == Status.SHUTDOWN) {
      return Status.SHUTDOWN;
    }

    return newStatus;
  }

  public BindableService getHealthService() {
    return statusManager.getHealthService();
  }

  private void updateGrpcHealthStatus(final Status status) {
    switch (status) {
      case RUNNING:
        setGrpcHealthStatus(ServingStatus.SERVING);
        break;
      case SHUTDOWN:
        statusManager.enterTerminalState();
        break;
      case INITIAL:
      case STARTING:
      default:
        setGrpcHealthStatus(ServingStatus.NOT_SERVING);
        break;
    }
  }

  private void setGrpcHealthStatus(final ServingStatus servingStatus) {
    MONITORED_SERVICES.forEach(service -> statusManager.setStatus(service, servingStatus));
  }
}
