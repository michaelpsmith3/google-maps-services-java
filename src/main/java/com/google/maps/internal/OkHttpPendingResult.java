package com.google.maps.internal;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.maps.PendingResult;
import com.google.maps.model.AddressComponentType;
import com.google.maps.model.AddressType;
import com.google.maps.model.Distance;
import com.google.maps.model.Duration;
import com.google.maps.model.TravelMode;

import com.squareup.okhttp.Call;
import com.squareup.okhttp.Callback;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import org.joda.time.DateTime;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Logger;

/**
 * A PendingResult backed by a HTTP call executed by OkHttp, a deserialization step using Gson,
 * rate limiting and a retry policy.
 *
 * <p>{@code T} is the type of the result of this pending result, and {@code R} is the type of the
 * request.
 */
public class OkHttpPendingResult<T, R extends ApiResponse<T>>
    implements PendingResult<T>, Callback {
  private final Request request;
  private final OkHttpClient client;
  private final Class<R> responseClass;
  private final FieldNamingPolicy fieldNamingPolicy;

  private Call call;
  private Callback<T> callback;
  private long errorTimeOut;
  private int retryCounter = 0;
  private long cumulativeSleepTime = 0;

  private static Logger log = Logger.getLogger(OkHttpPendingResult.class.getName());
  private static final List<Integer> RETRY_ERROR_CODES =  Arrays.asList(500, 503, 504);

  /**
   * @param request           HTTP request to execute.
   * @param client            The client used to execute the request.
   * @param responseClass     Model class to unmarshal JSON body content.
   * @param fieldNamingPolicy FieldNamingPolicy for unmarshaling JSON.
   * @param errorTimeOut      Number of milliseconds to re-send erroring requests.
   */
  public OkHttpPendingResult(Request request, OkHttpClient client, Class<R> responseClass,
      FieldNamingPolicy fieldNamingPolicy, long errorTimeOut) {
    this.request = request;
    this.client = client;
    this.responseClass = responseClass;
    this.fieldNamingPolicy = fieldNamingPolicy;
    this.errorTimeOut = errorTimeOut;

    this.call = client.newCall(request);
  }

  @Override
  public void setCallback(Callback<T> callback) {
    this.callback = callback;
    call.enqueue(this);
  }

  /**
   * Preserve a request/response pair through an asynchronous callback.
   */
  private class QueuedResponse {
    public final OkHttpPendingResult<T, R> request;
    public final Response response;
    public final Exception e;

    public QueuedResponse(OkHttpPendingResult<T, R> request, Response response) {
      this.request = request;
      this.response = response;
      this.e = null;
    }
    public QueuedResponse(OkHttpPendingResult<T, R> request, Exception e) {
      this.request = request;
      this.response = null;
      this.e = e;
    }
  }

  @Override
  public T await() throws Exception {
    // Handle sleeping for retried requests
    if (retryCounter > 0) {
      // 0.5 * (1.5 ^ i) represents an increased sleep time of 1.5x per iteration,
      // starting at 0.5s when i = 0. The retryCounter will be 1 for the 1st retry,
      // so subtract 1 here.
      double delaySecs = 0.5 * Math.pow(1.5, retryCounter - 1);

      // Generate a jitter value between -delaySecs / 2 and +delaySecs / 2
      long delayMillis = (long) (delaySecs * (Math.random() + 0.5) * 1000);

      log.config(String.format("Sleeping between errors for %dms (retry #%d, already slept %dms)",
          delayMillis, retryCounter, cumulativeSleepTime));
      cumulativeSleepTime += delayMillis;
      try {
        Thread.sleep(delayMillis);
      } catch (InterruptedException e) {
        // No big deal if we don't sleep as long as intended.
      }
    }

    final BlockingQueue<QueuedResponse> waiter = new ArrayBlockingQueue<>(1);
    final OkHttpPendingResult<T, R> parent = this;

    // This callback will be called on another thread, handled by the RateLimitExecutorService.
    // Calling call.execute() directly would bypass the rate limiting.
    call.enqueue(new com.squareup.okhttp.Callback() {
      @Override
      public void onFailure(Request request, IOException e) {
        waiter.add(new QueuedResponse(parent, e));
      }

      @Override
      public void onResponse(Response response) throws IOException {
        waiter.add(new QueuedResponse(parent, response));
      }
    });

    QueuedResponse r = waiter.take();
    if (r.response != null) {
      return parseResponse(r.request, r.response);
    } else {
      throw r.e;
    }
  }

  @Override
  public T awaitIgnoreError() {
    try {
      return await();
    } catch (Exception e) {
      return null;
    }
  }

  @Override
  public void cancel() {
    call.cancel();
  }

  @Override
  public void onFailure(Request request, IOException ioe) {
    if (callback != null) {
      callback.onFailure(ioe);
    }
  }

  @Override
  public void onResponse(Response response) throws IOException {
    if (callback != null) {
      try {
        callback.onResult(parseResponse(this, response));
      } catch (Exception e) {
        callback.onFailure(e);
      }
    }
  }

  private T parseResponse(OkHttpPendingResult<T, R> request, Response response) throws Exception {
    if (RETRY_ERROR_CODES.contains(response.code()) && cumulativeSleepTime < errorTimeOut) {
        // Retry is a blocking method, but that's OK. If we're here, we're either in an await()
        // call, which is blocking anyway, or we're handling a callback in a separate thread.
        return request.retry();
    } else if (!response.isSuccessful()) {
      // The APIs return 200 even when the API request fails, as long as the transport mechanism
      // succeeds. INVALID_RESPONSE, etc are handled by the Gson parsing below.
      throw new IOException(String.format("Server Error: %d %s", response.code(),
          response.message()));
    }

    Gson gson = new GsonBuilder()
        .registerTypeAdapter(AddressComponentType.class, new AddressComponentTypeAdapter())
        .registerTypeAdapter(AddressType.class, new AddressTypeAdapter())
        .registerTypeAdapter(DateTime.class, new DateTimeAdapter())
        .registerTypeAdapter(Distance.class, new DistanceAdapter())
        .registerTypeAdapter(Duration.class, new DurationAdapter())
        .registerTypeAdapter(TravelMode.class, new TravelModeAdapter())
        .setFieldNamingPolicy(fieldNamingPolicy)
        .create();

    InputStream in = response.body().byteStream();
    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
    // Handle illegal UTF-8 by skipping it.
    decoder.onMalformedInput(CodingErrorAction.IGNORE);
    Reader reader = new InputStreamReader(in, decoder);
    R resp = gson.fromJson(reader, responseClass);

    if (resp.successful()) {
      return resp.getResult();
    } else {
      throw resp.getError();
    }
  }

  private T retry() throws Exception {
    retryCounter++;
    log.info("Retrying request. Retry #" + retryCounter);
    this.call = client.newCall(request);
    return this.await();
  }
}