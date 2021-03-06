package org.folio.rest.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.folio.rest.jaxrs.model.RequestPolicies;
import org.folio.rest.jaxrs.model.RequestPolicy;
import org.folio.rest.jaxrs.resource.RequestPolicyStorage;
import org.folio.rest.persist.Criteria.Criteria;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.Criteria.Limit;
import org.folio.rest.persist.Criteria.Offset;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.cql.CQLWrapper;
import org.folio.rest.tools.utils.OutStream;
import org.folio.rest.tools.utils.TenantTool;
import org.folio.rest.tools.utils.ValidationHelper;
import org.z3950.zing.cql.cql2pgjson.CQL2PgJSON;

import javax.ws.rs.core.Response;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.folio.rest.impl.Headers.TENANT_HEADER;

public class RequestPoliciesAPI implements RequestPolicyStorage {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final String REQUEST_POLICY_TABLE = "request_policy";
  private static final Class<RequestPolicy> REQUEST_POLICY_CLASS = RequestPolicy.class;

  @Override
  public void getRequestPolicyStorageRequestPolicies(int offset, int limit, String query, String lang, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {

    String tenantId = okapiHeaders.get(TENANT_HEADER);

    try {
      vertxContext.runOnContext(v -> {
        try {
          PostgresClient postgresClient = PostgresClient.getInstance(
            vertxContext.owner(), TenantTool.calculateTenantId(tenantId));

          String[] fieldList = {"*"};

          CQL2PgJSON cql2pgJson = new CQL2PgJSON("request_policy.jsonb");
          CQLWrapper cql = new CQLWrapper(cql2pgJson, query)
            .setLimit(new Limit(limit))
            .setOffset(new Offset(offset));

          postgresClient.get(REQUEST_POLICY_TABLE,  REQUEST_POLICY_CLASS, fieldList, cql,
            true, false, reply -> {
              try {
                if(reply.succeeded()) {

                  @SuppressWarnings("unchecked")
                  List<RequestPolicy> requestPolicies = reply.result().getResults();

                  RequestPolicies pagedRequestPolicies = new RequestPolicies();
                  pagedRequestPolicies.setRequestPolicies(requestPolicies);
                  pagedRequestPolicies.setTotalRecords(reply.result().getResultInfo().getTotalRecords());

                  asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(
                    RequestPolicyStorage.GetRequestPolicyStorageRequestPoliciesResponse.
                      respond200WithApplicationJson(pagedRequestPolicies)));
                }
                else {
                  asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(
                    RequestPolicyStorage.GetRequestPolicyStorageRequestPoliciesResponse
                      .respond500WithTextPlain(reply.cause().getMessage())));
                }
              } catch (Exception e) {
                log.error(e);
                asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(
                  RequestPolicyStorage.GetRequestPolicyStorageRequestPoliciesResponse
                    .respond500WithTextPlain(e.getMessage())));
              }
            });
        } catch (Exception e) {
          log.error(e);
          asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(
            RequestPolicyStorage.GetRequestPolicyStorageRequestPoliciesResponse
            .respond500WithTextPlain(e.getMessage())));
        }
      });
    } catch (Exception e) {
      log.error(e);
      asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(
        RequestPolicyStorage.GetRequestPolicyStorageRequestPoliciesResponse
          .respond500WithTextPlain(e.getMessage())));
    }
  }

  @Override
  public void postRequestPolicyStorageRequestPolicies(String lang, RequestPolicy entity, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    String tenantId = okapiHeaders.get(TENANT_HEADER);

    try {
      PostgresClient postgresClient =
        PostgresClient.getInstance(
          vertxContext.owner(), TenantTool.calculateTenantId(tenantId));

      vertxContext.runOnContext(v -> {
        try {
          if(entity.getId() == null) {
            entity.setId(UUID.randomUUID().toString());
          }

          postgresClient.save(REQUEST_POLICY_TABLE, entity.getId(), entity,
            reply -> {
              try {
                if(reply.succeeded()) {
                  OutStream stream = new OutStream();
                  stream.setData(entity);

                  asyncResultHandler.handle(
                    io.vertx.core.Future.succeededFuture(
                      RequestPolicyStorage.PostRequestPolicyStorageRequestPoliciesResponse
                        .respond201WithApplicationJson(entity,
                          RequestPolicyStorage.PostRequestPolicyStorageRequestPoliciesResponse.headersFor201().withLocation(reply.result()))));
                }
                else {
                  asyncResultHandler.handle(
                    io.vertx.core.Future.succeededFuture(
                      RequestPolicyStorage.PostRequestPolicyStorageRequestPoliciesResponse
                        .respond500WithTextPlain(reply.cause().toString())));
                }
              } catch (Exception e) {
                log.error(e);
                asyncResultHandler.handle(
                  io.vertx.core.Future.succeededFuture(
                    RequestPolicyStorage.PostRequestPolicyStorageRequestPoliciesResponse
                      .respond500WithTextPlain(e.getMessage())));
              }
            });
        } catch (Exception e) {
          log.error(e);
          asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(
            RequestPolicyStorage.PostRequestPolicyStorageRequestPoliciesResponse
              .respond500WithTextPlain(e.getMessage())));
        }
      });
    } catch (Exception e) {
      log.error(e);
      asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(
        RequestPolicyStorage.PostRequestPolicyStorageRequestPoliciesResponse
          .respond500WithTextPlain(e.getMessage())));

    }
  }

  @Override
  public void deleteRequestPolicyStorageRequestPolicies(String lang, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {

    String tenantId = okapiHeaders.get(TENANT_HEADER);

    vertxContext.runOnContext(v -> {
      try {
        PostgresClient postgresClient = PostgresClient.getInstance(
          vertxContext.owner(), TenantTool.calculateTenantId(tenantId));

        CQL2PgJSON cql2pgJson = new CQL2PgJSON("request_policy.jsonb");
        CQLWrapper cql = new CQLWrapper(cql2pgJson, null);

        postgresClient.delete(REQUEST_POLICY_TABLE, cql,
          reply -> asyncResultHandler.handle(Future.succeededFuture(
            DeleteRequestPolicyStorageRequestPoliciesResponse.respond204())));
      }
      catch(Exception e) {
        asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(
          RequestPolicyStorage.DeleteRequestPolicyStorageRequestPoliciesResponse
            .respond500WithTextPlain(e.getMessage())));
      }
    });
  }

  @Override
  public void getRequestPolicyStorageRequestPoliciesByRequestPolicyId(String requestPolicyId, String lang, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    String tenantId = okapiHeaders.get(TENANT_HEADER);

    try {
      PostgresClient postgresClient = PostgresClient.getInstance(
        vertxContext.owner(), TenantTool.calculateTenantId(tenantId));

      Criterion criterion = getRequestByIdCriterion(requestPolicyId);

      vertxContext.runOnContext(v -> {
        try {
          postgresClient.get(REQUEST_POLICY_TABLE, REQUEST_POLICY_CLASS, criterion, true, false,
            reply -> {
              try {
                if (reply.succeeded()) {
                  @SuppressWarnings("unchecked")
                  List<RequestPolicy> requestPolicies = reply.result().getResults();

                  if (requestPolicies.size() == 1) {
                    RequestPolicy requestPolicy = requestPolicies.get(0);

                    asyncResultHandler.handle(
                      io.vertx.core.Future.succeededFuture(
                        RequestPolicyStorage.GetRequestPolicyStorageRequestPoliciesByRequestPolicyIdResponse.
                          respond200WithApplicationJson(requestPolicy)));
                  }
                  else {
                    asyncResultHandler.handle(
                      Future.succeededFuture(
                        RequestPolicyStorage.GetRequestPolicyStorageRequestPoliciesByRequestPolicyIdResponse.
                          respond404WithTextPlain(ValidationHelper.createValidationErrorMessage("name", RequestPolicy.class.getName(), "Not Found"))));
                  }
                } else {
                  asyncResultHandler.handle(
                    Future.succeededFuture(
                      RequestPolicyStorage.GetRequestPolicyStorageRequestPoliciesByRequestPolicyIdResponse
                        .respond500WithTextPlain(reply.cause().getMessage())));
                }
              } catch (Exception e) {
                log.error(e);
                asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(
                  RequestPolicyStorage.GetRequestPolicyStorageRequestPoliciesByRequestPolicyIdResponse
                  .respond500WithTextPlain(e.getMessage())));
              }
            });
        } catch (Exception e) {
          log.error(e);
          asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(
            RequestPolicyStorage.GetRequestPolicyStorageRequestPoliciesByRequestPolicyIdResponse
            .respond500WithTextPlain(e.getMessage())));
        }
      });
    } catch (Exception e) {
      log.error(e);
      asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(
        RequestPolicyStorage.
          GetRequestPolicyStorageRequestPoliciesByRequestPolicyIdResponse
            .respond500WithTextPlain(e.getMessage())));
    }
  }

  @Override
  public void putRequestPolicyStorageRequestPoliciesByRequestPolicyId(String requestPolicyId, String lang, RequestPolicy entity, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {

    String tenantId = okapiHeaders.get(TENANT_HEADER);

    try {
      PostgresClient postgresClient = PostgresClient.getInstance(
          vertxContext.owner(), TenantTool.calculateTenantId(tenantId));

      Criterion criterion = getRequestByIdCriterion(requestPolicyId);

      vertxContext.runOnContext(v ->
        postgresClient.get(REQUEST_POLICY_TABLE, REQUEST_POLICY_CLASS, criterion, true, false,
          reply -> {
            if (reply.succeeded()) {
              @SuppressWarnings("unchecked")
              List<RequestPolicy> requestPolicyList = reply.result().getResults();
              if (requestPolicyList.size() == 1) {
                postgresClient.update(REQUEST_POLICY_TABLE, entity, criterion,
                  true,
                  update -> {
                    if (update.succeeded()) {
                      OutStream stream = new OutStream();
                      stream.setData(entity);

                      asyncResultHandler.handle(
                        Future.succeededFuture(PutRequestPolicyStorageRequestPoliciesByRequestPolicyIdResponse
                          .respond204()));
                    } else {
                      asyncResultHandler.handle(
                        Future.succeededFuture(PutRequestPolicyStorageRequestPoliciesByRequestPolicyIdResponse
                          .respond500WithTextPlain(update.cause().getMessage())));
                    }
                  });
              } else {
                postgresClient.save(REQUEST_POLICY_TABLE, entity.getId(), entity,
                  save -> {
                    if (save.succeeded()) {
                      OutStream stream = new OutStream();
                      stream.setData(entity);

                      asyncResultHandler.handle(
                        Future.succeededFuture(PutRequestPolicyStorageRequestPoliciesByRequestPolicyIdResponse
                          .respond204()));
                    } else {
                      asyncResultHandler.handle(
                        Future.succeededFuture(PutRequestPolicyStorageRequestPoliciesByRequestPolicyIdResponse
                          .respond500WithTextPlain(save.cause().getMessage())));
                    }
                  });
              }
            } else {
              asyncResultHandler.handle(Future.succeededFuture(PutRequestPolicyStorageRequestPoliciesByRequestPolicyIdResponse
                .respond500WithTextPlain(reply.cause().getMessage())));
            }
          })
      );
    } catch (Exception e) {
      asyncResultHandler.handle(Future.succeededFuture(PutRequestPolicyStorageRequestPoliciesByRequestPolicyIdResponse
        .respond500WithTextPlain(e.getMessage())));
    }
  }

  @Override
  public void deleteRequestPolicyStorageRequestPoliciesByRequestPolicyId(String requestPolicyId, String lang, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {

      String tenantId = okapiHeaders.get(TENANT_HEADER);

      try {
        PostgresClient postgresClient =
          PostgresClient.getInstance(
            vertxContext.owner(), TenantTool.calculateTenantId(tenantId));

        Criterion criterion = getRequestByIdCriterion(requestPolicyId);

        vertxContext.runOnContext(v -> {
          try {
            postgresClient.delete(REQUEST_POLICY_TABLE, criterion,
              reply -> {
                if(reply.succeeded()) {
                  asyncResultHandler.handle(
                    Future.succeededFuture(
                      DeleteRequestPolicyStorageRequestPoliciesByRequestPolicyIdResponse
                        .respond204()));
                }
                else {
                  asyncResultHandler.handle(Future.succeededFuture(
                    DeleteRequestPolicyStorageRequestPoliciesByRequestPolicyIdResponse
                      .respond500WithTextPlain(reply.cause().getMessage())));
                }
              });
          } catch (Exception e) {
            asyncResultHandler.handle(Future.succeededFuture(
              DeleteRequestPolicyStorageRequestPoliciesByRequestPolicyIdResponse
                .respond500WithTextPlain(e.getMessage())));
          }
        });
      } catch (Exception e) {
        asyncResultHandler.handle(Future.succeededFuture(
          DeleteRequestPolicyStorageRequestPoliciesByRequestPolicyIdResponse
            .respond500WithTextPlain(e.getMessage())));
      }
  }

  private Criterion getRequestByIdCriterion( String id){

    Criteria a = new Criteria();
    a.addField("'id'");
    a.setOperation("=");
    a.setValue(id);
    return new Criterion(a);
  }
}
