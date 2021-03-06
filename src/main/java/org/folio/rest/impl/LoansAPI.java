package org.folio.rest.impl;

import static io.vertx.core.Future.succeededFuture;
import static org.folio.rest.impl.Headers.TENANT_HEADER;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.Function;

import javax.validation.constraints.NotNull;
import javax.ws.rs.core.Response;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.folio.rest.annotations.Validate;
import org.folio.rest.jaxrs.model.Error;
import org.folio.rest.jaxrs.model.Errors;
import org.folio.rest.jaxrs.model.Loan;
import org.folio.rest.jaxrs.model.Loans;
import org.folio.rest.jaxrs.model.Status;
import org.folio.rest.jaxrs.resource.LoanStorage;
import org.folio.rest.persist.Criteria.Criteria;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.Criteria.Limit;
import org.folio.rest.persist.Criteria.Offset;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.cql.CQLWrapper;
import org.folio.rest.tools.utils.TenantTool;
import org.folio.rest.tools.utils.ValidationHelper;
import org.folio.support.ResultHandlerFactory;
import org.folio.support.ServerErrorResponder;
import org.folio.support.UUIDValidation;
import org.folio.support.VertxContextRunner;
import org.joda.time.DateTime;
import org.z3950.zing.cql.cql2pgjson.CQL2PgJSON;

import com.github.mauricio.async.db.postgresql.exceptions.GenericDatabaseException;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

public class LoansAPI implements LoanStorage {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final String MODULE_NAME = "mod_circulation_storage";
  private static final String LOAN_TABLE = "loan";
  //TODO: Change loan history table name when can be configured, used to be "loan_history_table"
  private static final String LOAN_HISTORY_TABLE = "audit_loan";

  private static final Class<Loan> LOAN_CLASS = Loan.class;
  private static final String OPEN_LOAN_STATUS = "Open";

  public LoansAPI(Vertx vertx, String tenantId) {
    PostgresClient.getInstance(vertx, tenantId).setIdField("_id");
  }

  @Override
  public void deleteLoanStorageLoans(
    String lang,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) {

    String tenantId = okapiHeaders.get(TENANT_HEADER);

    vertxContext.runOnContext(v -> {
      try {
        PostgresClient postgresClient = PostgresClient.getInstance(
          vertxContext.owner(), TenantTool.calculateTenantId(tenantId));

        postgresClient.mutate(String.format("TRUNCATE TABLE %s_%s.loan",
          tenantId, MODULE_NAME),
          reply -> asyncResultHandler.handle(succeededFuture(
            DeleteLoanStorageLoansResponse.respond204())));
      }
      catch(Exception e) {
        asyncResultHandler.handle(succeededFuture(
          LoanStorage.DeleteLoanStorageLoansResponse
            .respond500WithTextPlain(e.getMessage())));
      }
    });
  }

  @Validate
  @Override
  public void getLoanStorageLoans(
    int offset,
    int limit,
    String query,
    String lang,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) {

    String tenantId = okapiHeaders.get(TENANT_HEADER);

    try {
      vertxContext.runOnContext(v -> {
        try {
          PostgresClient postgresClient = PostgresClient.getInstance(
            vertxContext.owner(), TenantTool.calculateTenantId(tenantId));

          log.info("CQL Query: " + query);

          String[] fieldList = {"*"};

          CQL2PgJSON cql2pgJson = new CQL2PgJSON("loan.jsonb");
          CQLWrapper cql = new CQLWrapper(cql2pgJson, query)
            .setLimit(new Limit(limit))
            .setOffset(new Offset(offset));

          postgresClient.get(LOAN_TABLE, LOAN_CLASS, fieldList, cql,
            true, false, reply -> {
              try {
                if(reply.succeeded()) {
                  @SuppressWarnings("unchecked")
                  List<Loan> loans = (List<Loan>) reply.result().getResults();

                  Loans pagedLoans = new Loans();
                  pagedLoans.setLoans(loans);
                  pagedLoans.setTotalRecords(reply.result().getResultInfo().getTotalRecords());

                  asyncResultHandler.handle(succeededFuture(
                    LoanStorage.GetLoanStorageLoansResponse.
                      respond200WithApplicationJson(pagedLoans)));
                }
                else {
                  asyncResultHandler.handle(succeededFuture(
                    LoanStorage.GetLoanStorageLoansResponse.
                      respond500WithTextPlain(reply.cause().getMessage())));
                }
              } catch (Exception e) {
                log.error(e);
                asyncResultHandler.handle(succeededFuture(
                  LoanStorage.GetLoanStorageLoansResponse.
                    respond500WithTextPlain(e.getMessage())));
              }
            });
        } catch (Exception e) {
          log.error(e);
          asyncResultHandler.handle(succeededFuture(
            LoanStorage.GetLoanStorageLoansResponse.
              respond500WithTextPlain(e.getMessage())));
        }
      });
    } catch (Exception e) {
      log.error(e);
      asyncResultHandler.handle(succeededFuture(
        LoanStorage.GetLoanStorageLoansResponse.
          respond500WithTextPlain(e.getMessage())));
    }
  }

  @Override
  public void postLoanStorageLoans(
    String lang,
    Loan loan,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) {

    String tenantId = okapiHeaders.get(TENANT_HEADER);

    if(loan.getStatus() == null) {
      loan.setStatus(new Status().withName(OPEN_LOAN_STATUS));
    }

    if(isOpenAndHasNoUserId(loan)) {
      respondWithError(asyncResultHandler,
        PostLoanStorageLoansResponse::respond422WithApplicationJson,
        "Open loan must have a user ID");
      return;
    }

    //TODO: Convert this to use validation responses (422 and error of errors)
    ImmutablePair<Boolean, String> validationResult = validateLoan(loan);

    if(!validationResult.getLeft()) {
      asyncResultHandler.handle(
        succeededFuture(
          LoanStorage.PostLoanStorageLoansResponse
            .respond400WithTextPlain(
              validationResult.getRight())));

      return;
    }

    try {
      PostgresClient postgresClient =
        PostgresClient.getInstance(
          vertxContext.owner(), TenantTool.calculateTenantId(tenantId));

      vertxContext.runOnContext(v -> {
        try {

          if(loan.getId() == null) {
            loan.setId(UUID.randomUUID().toString());
          }

          postgresClient.save(LOAN_TABLE, loan.getId(), loan,
            reply -> {
              try {
                if(reply.succeeded()) {
                  asyncResultHandler.handle(
                    succeededFuture(
                      LoanStorage.PostLoanStorageLoansResponse
                        .respond201WithApplicationJson(loan,
                          PostLoanStorageLoansResponse.headersFor201().withLocation(reply.result()))));
                }
                else {
                  if(isMultipleOpenLoanError(reply)) {
                    asyncResultHandler.handle(
                      succeededFuture(LoanStorage.PostLoanStorageLoansResponse
                      .respond422WithApplicationJson(moreThanOneOpenLoanError(loan))));
                  }
                  else {
                    asyncResultHandler.handle(
                      succeededFuture(
                        LoanStorage.PostLoanStorageLoansResponse
                          .respond500WithTextPlain(reply.cause().toString())));
                  }
                }
              } catch (Exception e) {
                log.error(e);
                asyncResultHandler.handle(
                  succeededFuture(
                    LoanStorage.PostLoanStorageLoansResponse
                      .respond500WithTextPlain(e.getMessage())));
              }
            });
        } catch (Exception e) {
          log.error(e);
          asyncResultHandler.handle(succeededFuture(
            LoanStorage.PostLoanStorageLoansResponse
              .respond500WithTextPlain(e.getMessage())));
        }
      });
    } catch (Exception e) {
      log.error(e);
      asyncResultHandler.handle(succeededFuture(
        LoanStorage.PostLoanStorageLoansResponse
          .respond500WithTextPlain(e.getMessage())));
    }
  }

  @Override
  public void postLoanStorageLoansAnonymizeByUserId(
    String userId,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> responseHandler,
    Context vertxContext) {

    final ServerErrorResponder serverErrorResponder =
      new ServerErrorResponder(PostLoanStorageLoansAnonymizeByUserIdResponse
        ::respond500WithTextPlain, responseHandler, log);

    final VertxContextRunner runner = new VertxContextRunner(
      vertxContext, serverErrorResponder::withError);

    runner.runOnContext(() -> {
      if(!UUIDValidation.isValidUUID(userId)) {
        final Errors errors = ValidationHelper.createValidationErrorMessage(
          "userId", userId, "Invalid user ID, should be a UUID");

        responseHandler.handle(succeededFuture(
          PostLoanStorageLoansAnonymizeByUserIdResponse
            .respond422WithApplicationJson(errors)));
        return;
      }

      final String tenantId = TenantTool.tenantId(okapiHeaders);

      final PostgresClient postgresClient = PostgresClient.getInstance(
          vertxContext.owner(), tenantId);

      final String combinedAnonymizationSql = createAnonymizationSQL(userId,
        tenantId);

      log.info(String.format("Anonymization SQL: %s", combinedAnonymizationSql));

      postgresClient.mutate(combinedAnonymizationSql,
        new ResultHandlerFactory().when(
          s -> responseHandler.handle(succeededFuture(
            PostLoanStorageLoansAnonymizeByUserIdResponse.respond204())),
          serverErrorResponder::withError));
    });
  }

  @Validate
  @Override
  public void getLoanStorageLoansByLoanId(
    String loanId,
    String lang,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) {

    String tenantId = okapiHeaders.get(TENANT_HEADER);

    try {
      PostgresClient postgresClient = PostgresClient.getInstance(
        vertxContext.owner(), TenantTool.calculateTenantId(tenantId));

      Criteria a = new Criteria();

      a.addField("'id'");
      a.setOperation("=");
      a.setValue(loanId);

      Criterion criterion = new Criterion(a);

      vertxContext.runOnContext(v -> {
        try {
          postgresClient.get(LOAN_TABLE, LOAN_CLASS, criterion, true, false,
            reply -> {
              try {
                if (reply.succeeded()) {
                  @SuppressWarnings("unchecked")
                  List<Loan> loans = (List<Loan>) reply.result().getResults();

                  if (loans.size() == 1) {
                    Loan loan = loans.get(0);

                    asyncResultHandler.handle(
                      succeededFuture(
                        LoanStorage.GetLoanStorageLoansByLoanIdResponse.
                          respond200WithApplicationJson(loan)));
                  }
                  else {
                    asyncResultHandler.handle(
                      succeededFuture(
                        LoanStorage.GetLoanStorageLoansByLoanIdResponse.
                          respond404WithTextPlain("Not Found")));
                  }
                } else {
                  asyncResultHandler.handle(
                    succeededFuture(
                      LoanStorage.GetLoanStorageLoansByLoanIdResponse.
                        respond500WithTextPlain(reply.cause().getMessage())));

                }
              } catch (Exception e) {
                log.error(e);
                asyncResultHandler.handle(succeededFuture(
                  LoanStorage.GetLoanStorageLoansByLoanIdResponse.
                    respond500WithTextPlain(e.getMessage())));
              }
            });
        } catch (Exception e) {
          log.error(e);
          asyncResultHandler.handle(succeededFuture(
            LoanStorage.GetLoanStorageLoansByLoanIdResponse.
              respond500WithTextPlain(e.getMessage())));
        }
      });
    } catch (Exception e) {
      log.error(e);
      asyncResultHandler.handle(succeededFuture(
        LoanStorage.GetLoanStorageLoansByLoanIdResponse.
          respond500WithTextPlain(e.getMessage())));
    }
  }

  @Override
  public void deleteLoanStorageLoansByLoanId(
    String loanId,
    String lang,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) {

    String tenantId = okapiHeaders.get(TENANT_HEADER);

    try {
      PostgresClient postgresClient =
        PostgresClient.getInstance(
          vertxContext.owner(), TenantTool.calculateTenantId(tenantId));

      Criteria a = new Criteria();

      a.addField("'id'");
      a.setOperation("=");
      a.setValue(loanId);

      Criterion criterion = new Criterion(a);

      vertxContext.runOnContext(v -> {
        try {
          postgresClient.delete(LOAN_TABLE, criterion,
            reply -> {
              if(reply.succeeded()) {
                asyncResultHandler.handle(
                  succeededFuture(
                    DeleteLoanStorageLoansByLoanIdResponse
                      .respond204()));
              }
              else {
                asyncResultHandler.handle(succeededFuture(
                  DeleteLoanStorageLoansByLoanIdResponse
                    .respond500WithTextPlain(reply.cause().getMessage())));
              }
            });
        } catch (Exception e) {
          asyncResultHandler.handle(succeededFuture(
            DeleteLoanStorageLoansByLoanIdResponse
              .respond500WithTextPlain(e.getMessage())));
        }
      });
    } catch (Exception e) {
      asyncResultHandler.handle(succeededFuture(
        DeleteLoanStorageLoansByLoanIdResponse
          .respond500WithTextPlain(e.getMessage())));
    }
  }

  @Override
  public void putLoanStorageLoansByLoanId(
    String loanId,
    String lang,
    Loan loan,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) {

    String tenantId = okapiHeaders.get(TENANT_HEADER);

    if(loan.getStatus() == null) {
      loan.setStatus(new Status().withName(OPEN_LOAN_STATUS));
    }

    ImmutablePair<Boolean, String> validationResult = validateLoan(loan);

    if(!validationResult.getLeft()) {
      asyncResultHandler.handle(
        succeededFuture(
          LoanStorage.PutLoanStorageLoansByLoanIdResponse
            .respond400WithTextPlain(
              validationResult.getRight())));

      return;
    }

    if(isOpenAndHasNoUserId(loan)) {
      respondWithError(asyncResultHandler,
        PutLoanStorageLoansByLoanIdResponse::respond422WithApplicationJson,
        "Open loan must have a user ID");
      return;
    }

    try {
      PostgresClient postgresClient =
        PostgresClient.getInstance(
          vertxContext.owner(), TenantTool.calculateTenantId(tenantId));

      Criteria a = new Criteria();

      a.addField("'id'");
      a.setOperation("=");
      a.setValue(loanId);

      Criterion criterion = new Criterion(a);

      vertxContext.runOnContext(v -> {
        try {
          postgresClient.get(LOAN_TABLE, LOAN_CLASS, criterion, true, false,
            reply -> {
              if(reply.succeeded()) {
                @SuppressWarnings("unchecked")
                List<Loan> loanList = (List<Loan>) reply.result().getResults();

                if (loanList.size() == 1) {
                  try {
                    postgresClient.update(LOAN_TABLE, loan, criterion,
                      true,
                      update -> {
                        try {
                          if(update.succeeded()) {
                            asyncResultHandler.handle(
                              succeededFuture(
                                PutLoanStorageLoansByLoanIdResponse
                                  .respond204()));
                          }
                          else {
                            if(isMultipleOpenLoanError(update)) {
                              asyncResultHandler.handle(
                                succeededFuture(
                                  LoanStorage.PutLoanStorageLoansByLoanIdResponse
                                  .respond422WithApplicationJson(
                                    moreThanOneOpenLoanError(loan))));
                            }
                            else {
                              asyncResultHandler.handle(
                                succeededFuture(
                                  LoanStorage.PutLoanStorageLoansByLoanIdResponse
                                    .respond500WithTextPlain(update.cause().toString())));
                            }
                          }
                        } catch (Exception e) {
                          asyncResultHandler.handle(
                            succeededFuture(
                              PutLoanStorageLoansByLoanIdResponse
                                .respond500WithTextPlain(e.getMessage())));
                        }
                      });
                  } catch (Exception e) {
                    asyncResultHandler.handle(succeededFuture(
                      PutLoanStorageLoansByLoanIdResponse
                        .respond500WithTextPlain(e.getMessage())));
                  }
                }
                else {
                  try {
                    postgresClient.save(LOAN_TABLE, loan.getId(), loan,
                      save -> {
                        try {
                          if(save.succeeded()) {
                            asyncResultHandler.handle(
                              succeededFuture(
                                PutLoanStorageLoansByLoanIdResponse
                                  .respond204()));
                          }
                          else {
                            if(isMultipleOpenLoanError(save)) {
                              asyncResultHandler.handle(
                                succeededFuture(
                                  LoanStorage.PutLoanStorageLoansByLoanIdResponse
                                  .respond422WithApplicationJson(
                                    moreThanOneOpenLoanError(loan))));
                            }
                            else {
                              asyncResultHandler.handle(
                                succeededFuture(
                                  LoanStorage.PostLoanStorageLoansResponse
                                    .respond500WithTextPlain(save.cause().toString())));
                            }
                          }
                        } catch (Exception e) {
                          asyncResultHandler.handle(
                            succeededFuture(
                              PutLoanStorageLoansByLoanIdResponse
                                .respond500WithTextPlain(e.getMessage())));
                        }
                      });
                  } catch (Exception e) {
                    asyncResultHandler.handle(succeededFuture(
                      PutLoanStorageLoansByLoanIdResponse
                        .respond500WithTextPlain(e.getMessage())));
                  }
                }
              } else {
                asyncResultHandler.handle(succeededFuture(
                  PutLoanStorageLoansByLoanIdResponse
                    .respond500WithTextPlain(reply.cause().getMessage())));
              }
            });
        } catch (Exception e) {
          asyncResultHandler.handle(succeededFuture(
            PutLoanStorageLoansByLoanIdResponse
              .respond500WithTextPlain(e.getMessage())));
        }
      });
    } catch (Exception e) {
      asyncResultHandler.handle(succeededFuture(
        PutLoanStorageLoansByLoanIdResponse
          .respond500WithTextPlain(e.getMessage())));
    }
  }

  private ImmutablePair<Boolean, String> validateLoan(Loan loan) {

    Boolean valid = true;
    StringJoiner messages = new StringJoiner("\n");

    //ISO8601 is less strict than RFC3339 so will not catch some issues
    try {
      DateTime.parse(loan.getLoanDate());
    }
    catch(Exception e) {
      valid = false;
      messages.add("loan date must be a date time (in RFC3339 format)");
    }

    if(loan.getReturnDate() != null) {
      //ISO8601 is less strict than RFC3339 so will not catch some issues
      try {
        DateTime.parse(loan.getReturnDate());
      }
      catch(Exception e) {
        valid = false;
        messages.add("return date must be a date time (in RFC3339 format)");
      }
    }

    return new ImmutablePair<>(valid, messages.toString());
  }

  @Validate
  @Override
  public void getLoanStorageLoanHistory(int offset, int limit, String query, String lang,
      Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler,
      Context vertxContext) {

    String tenantId = okapiHeaders.get(TENANT_HEADER);

    try {
      vertxContext.runOnContext(v -> {
        try {
          PostgresClient postgresClient = PostgresClient.getInstance(
            vertxContext.owner(), TenantTool.calculateTenantId(tenantId));

          String[] fieldList = {"*"};
          CQLWrapper cql = null;
          String adjustedQuery = null;
          CQL2PgJSON cql2pgJson = new CQL2PgJSON(LOAN_HISTORY_TABLE+".jsonb");
          if(query != null){
            //a bit of a hack, assume that <space>sortBy<space>
            //is a sort request that is received as part of the cql , and hence pass
            //the cql as is. If no sorting is requested, sort by created_date column
            //in the loan history table which represents the date the entry was created
            //aka the date an action was made on the loan
            if(!query.contains(" sortBy ")){
              cql = new CQLWrapper(cql2pgJson, query);
              adjustedQuery = cql.toString() + " order by created_date desc ";
              adjustedQuery = adjustedQuery + new Limit(limit).toString() + " " +new Offset(offset).toString();
            } else{
              cql = new CQLWrapper(cql2pgJson, query)
                  .setLimit(new Limit(limit))
                  .setOffset(new Offset(offset));
              adjustedQuery = cql.toString();
            }

            log.debug("CQL Query: " + cql.toString());

          } else {
            cql = new CQLWrapper(cql2pgJson, query)
                  .setLimit(new Limit(limit))
                  .setOffset(new Offset(offset));
            adjustedQuery = cql.toString();
          }

          postgresClient.get(LOAN_HISTORY_TABLE, LOAN_CLASS, fieldList, adjustedQuery,
            true, false, reply -> {
              try {
                if(reply.succeeded()) {
                  @SuppressWarnings("unchecked")
                  List<Loan> loans = (List<Loan>) reply.result().getResults();

                  Loans pagedLoans = new Loans();
                  pagedLoans.setLoans(loans);
                  pagedLoans.setTotalRecords(reply.result().getResultInfo().getTotalRecords());

                  asyncResultHandler.handle(succeededFuture(
                    GetLoanStorageLoanHistoryResponse.
                      respond200WithApplicationJson(pagedLoans)));
                }
                else {
                  log.error(reply.cause().getMessage(), reply.cause());
                  asyncResultHandler.handle(succeededFuture(
                    GetLoanStorageLoanHistoryResponse.
                      respond500WithTextPlain(reply.cause().getMessage())));
                }
              } catch (Exception e) {
                log.error(e.getMessage(), e);
                asyncResultHandler.handle(succeededFuture(
                  GetLoanStorageLoanHistoryResponse.
                    respond500WithTextPlain(e.getMessage())));
              }
            });
        } catch (Exception e) {
          log.error(e.getMessage(), e);
          asyncResultHandler.handle(succeededFuture(
            GetLoanStorageLoanHistoryResponse.
              respond500WithTextPlain(e.getMessage())));
        }
      });
    } catch (Exception e) {
      log.error(e.getMessage(), e);
      asyncResultHandler.handle(succeededFuture(
        GetLoanStorageLoanHistoryResponse.
          respond500WithTextPlain(e.getMessage())));
    }
  }

  private Errors moreThanOneOpenLoanError(Loan entity) {
    return ValidationHelper.createValidationErrorMessage(
      "itemId", entity.getItemId(),
      "Cannot have more than one open loan for the same item");
  }

  private <T> boolean isMultipleOpenLoanError(AsyncResult<T> reply) {
    return reply.cause() instanceof GenericDatabaseException &&
      ((GenericDatabaseException) reply.cause()).errorMessage().message()
        .contains("loan_itemid_idx_unique");
  }

  private boolean isOpenAndHasNoUserId(Loan loan) {
    return Objects.equals(loan.getStatus().getName(), OPEN_LOAN_STATUS)
      && loan.getUserId() == null;
  }

  private void respondWithError(
    Handler<AsyncResult<Response>> asyncResultHandler,
    Function<Errors, Response> responseCreator,
    String message) {

    final ArrayList<Error> errorsList = new ArrayList<>();

    errorsList.add(new Error().withMessage(message));

    final Errors errors = new Errors()
      .withErrors(errorsList);

    asyncResultHandler.handle(succeededFuture(
      responseCreator.apply(errors)));
  }

  private String createAnonymizationSQL(
    @NotNull String userId,
    String tenantId) {

    final String anonymizeLoansSql = String.format(
      "UPDATE %s_%s.loan SET jsonb = jsonb - 'userId'"
        + " WHERE jsonb->>'userId' = '" + userId + "'"
        + " AND jsonb->'status'->>'name' = 'Closed'",
      tenantId, MODULE_NAME);

    //Only anonymize the history for loans that are currently closed
    //meaning that we need to refer to loans in this query
    final String anonymizeLoansActionHistorySql = String.format(
      "UPDATE %s_%s.%s SET jsonb = jsonb - 'userId'"
        + " WHERE jsonb->>'userId' = '" + userId + "'"
        + " AND jsonb->>'id' IN (SELECT l.jsonb->>'id'" +
        " FROM %s_%s.loan l WHERE l.jsonb->>'userId' = '" + userId + "'"
        + " AND l.jsonb->'status'->>'name' = 'Closed')",
      tenantId, MODULE_NAME, LOAN_HISTORY_TABLE,
      tenantId, MODULE_NAME);

    //Loan action history needs to go first, as needs to be for specific loans
    return anonymizeLoansActionHistorySql + "; " + anonymizeLoansSql;
  }

}
