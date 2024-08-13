package selsup;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class CrptApi {
    private static final String API_URL = "https://ismp.crpt.ru/api/v3/lk/documents/create";

    private final RateLimiter rateLimiter;
    private final HttpClient httpClient;
    private final Gson gson;

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this.rateLimiter = new RateLimiter(timeUnit.toSeconds(requestLimit));
        this.httpClient = HttpClient.newHttpClient();
        this.gson = new Gson();
    }

    public void createDocument(DocumentRequest documentRequest, String signature) throws IOException, InterruptedException {
        Callable<Void> task = () -> {
            rateLimiter.acquire();
            HttpRequest request = buildHttpRequest(documentRequest, signature);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            handleResponse(response);
            return null;
        };

        try {
            Executors.newSingleThreadExecutor().submit(task).get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            } else {
                throw new RuntimeException("Unexpected error", e.getCause());
            }
        }
    }

    private HttpRequest buildHttpRequest(DocumentRequest documentRequest, String signature) {
        return HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("X-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(documentRequest)))
                .build();
    }

    private void handleResponse(HttpResponse<String> response) throws IOException {
        if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException(response.body());
        }
    }

    public record DocumentRequest(
            Description description,
            String doc_id,
            String doc_status,
            String doc_type,
            boolean importRequest,
            String owner_inn,
            String participant_inn,
            String producer_inn,
            String production_date,
            String production_type,
            Product[] products,
            String reg_date,
            String reg_number) {
    }

    public record Description(String participantInn) {
    }

    public record Product(
            String certificate_document,
            String certificate_document_date,
            String certificate_document_number,
            String owner_inn,
            String producer_inn,
            String production_date,
            String tnved_code,
            String uit_code,
            String uitu_code) {
    }


    private class RateLimiter {
        private final double permitsPerSecond;
        private final ReentrantLock lock;
        private final Condition condition;

        private double availablePermits;
        private Instant lastAcquireTime;

        public RateLimiter(double permitsPerSecond) {
            System.out.println("permitsPerSecond " + permitsPerSecond);
            this.permitsPerSecond = permitsPerSecond;
            this.lock = new ReentrantLock();
            this.condition = lock.newCondition();
            this.availablePermits = permitsPerSecond;
            this.lastAcquireTime = Instant.now();
        }

        public void acquire() {
            lock.lock();
            try {
                updateAvailablePermits();
                while (availablePermits < 1.0) {
                    condition.await(1, TimeUnit.NANOSECONDS);
                    updateAvailablePermits();
                }
                availablePermits--;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }

        private void updateAvailablePermits() {
            Instant now = Instant.now();
            double elapsedSeconds = (now.toEpochMilli() - lastAcquireTime.toEpochMilli()) / 1000.0;
            availablePermits = Math.min(permitsPerSecond, availablePermits + elapsedSeconds * permitsPerSecond);
            lastAcquireTime = now;
        }
    }
}
