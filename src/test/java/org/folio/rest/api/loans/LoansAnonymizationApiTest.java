package org.folio.rest.api.loans;

import static org.folio.rest.support.matchers.HttpResponseStatusCodeMatchers.isNoContent;
import static org.hamcrest.junit.MatcherAssert.assertThat;

import java.net.MalformedURLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.folio.rest.api.StorageTestSuite;
import org.folio.rest.support.ApiTests;
import org.folio.rest.support.ResponseHandler;
import org.folio.rest.support.TextResponse;
import org.folio.rest.support.http.InterfaceUrls;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LoansAnonymizationApiTest extends ApiTests {
  @Before
  public void beforeEach()
    throws MalformedURLException {

    StorageTestSuite.deleteAll(InterfaceUrls.loanStorageUrl());
  }

  @After
  public void checkIdsAfterEach() {
    StorageTestSuite.checkForMismatchedIDs("loan");
  }

  @Test
  public void shouldDoNothingWhenNoLoansForUser()
    throws MalformedURLException,
    ExecutionException,
    InterruptedException,
    TimeoutException {

    final UUID unknownUser = UUID.randomUUID();

    final CompletableFuture<TextResponse> postCompleted = new CompletableFuture<>();

    client.post(InterfaceUrls.loanStorageUrl("/anonymize/" + unknownUser),
      StorageTestSuite.TENANT_ID, ResponseHandler.text(postCompleted));

    final TextResponse postResponse = postCompleted.get(5, TimeUnit.SECONDS);

    assertThat(postResponse, isNoContent());
  }
}
