import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

/**
 * PDC Benchmark Server
 * Group: Naqash Ajmal (098), M. Abdul Rehman Moiz (079), Amir Hamza (064)
 *
 * Compile:  javac BenchmarkServer.java
 * Run:      java BenchmarkServer
 */
public class BenchmarkServer {

    //static final int PORT = 8080;
    static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // Serve the frontend HTML
        server.createContext("/", new StaticHandler());

        // API endpoints
        server.createContext("/api/benchmark/sequential",  new SequentialHandler());
        server.createContext("/api/benchmark/parallel",    new ParallelHandler());
        server.createContext("/api/benchmark/producer",    new ProducerConsumerHandler());
        server.createContext("/api/benchmark/sync",        new SyncDemoHandler());
        server.createContext("/api/benchmark/matrix",      new MatrixHandler());

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║   PDC Benchmark Server  — PORT " + PORT + "      ║");
        System.out.println("║   Open: http://localhost:" + PORT + "            ║");
        System.out.println("║   Group: Naqash 098 | Moiz 079 | Hamza 064 ║");
        System.out.println("╚══════════════════════════════════════════╝");
    }

    // ─── CORS + JSON helpers ──────────────────────────────────────────────────

    static void sendJson(HttpExchange ex, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
        if (ex.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            ex.sendResponseHeaders(204, -1);
            return;
        }
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    static String getParam(HttpExchange ex, String key, String def) {
        String query = ex.getRequestURI().getQuery();
        if (query == null) return def;
        for (String part : query.split("&")) {
            String[] kv = part.split("=");
            if (kv.length == 2 && kv[0].equals(key)) return kv[1];
        }
        return def;
    }

    // ─── Serve index.html ─────────────────────────────────────────────────────
    static class StaticHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            File f = new File("index.html");
            if (!f.exists()) {
                String msg = "index.html not found. Place index.html next to BenchmarkServer.java";
                ex.sendResponseHeaders(404, msg.length());
                ex.getResponseBody().write(msg.getBytes());
                ex.getResponseBody().close();
                return;
            }
            byte[] bytes = Files.readAllBytes(f.toPath());
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.getResponseBody().close();
        }
    }

    // =========================================================================
    // 1. SEQUENTIAL BENCHMARK
    //    Counts primes up to N using a simple trial-division loop.
    //    One thread, no parallelism — baseline timing.
    // =========================================================================
    static class SequentialHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            int n = Integer.parseInt(getParam(ex, "n", "500000"));

            long start = System.nanoTime();
            int count = countPrimesSequential(n);
            long elapsed = System.nanoTime() - start;

            double ms = elapsed / 1_000_000.0;

            String json = String.format(
                "{\"mode\":\"sequential\",\"n\":%d,\"primes\":%d,\"timeMs\":%.3f," +
                "\"threads\":1,\"log\":\"Sequential: counted %d primes up to %d in %.3f ms\"}",
                n, count, ms, count, n, ms
            );
            sendJson(ex, json);
        }

        int countPrimesSequential(int n) {
            int count = 0;
            for (int i = 2; i <= n; i++) {
                if (isPrime(i)) count++;
            }
            return count;
        }
    }

    // =========================================================================
    // 2. PARALLEL BENCHMARK
    //    Divides the range [2..N] into T chunks, each chunk runs in its own
    //    thread via ExecutorService. Uses AtomicInteger for thread-safe count.
    //    Demonstrates: multithreading, task distribution, parallel computation.
    // =========================================================================
    static class ParallelHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            int n       = Integer.parseInt(getParam(ex, "n", "500000"));
            int threads = Integer.parseInt(getParam(ex, "threads", "4"));

            // Thread-safe counter (AtomicInteger — no explicit lock needed here)
            AtomicInteger totalPrimes = new AtomicInteger(0);

            // Thread tracking for the UI
            long[] threadTimes = new long[threads];
            int[]  threadCounts = new int[threads];

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<?>> futures = new ArrayList<>();

            int chunkSize = n / threads;
            long wallStart = System.nanoTime();

            for (int t = 0; t < threads; t++) {
                final int tid   = t;
                final int from  = t * chunkSize + 2;
                final int to    = (t == threads - 1) ? n : (t + 1) * chunkSize;

                futures.add(pool.submit(() -> {
                    long tStart = System.nanoTime();
                    int localCount = 0;
                    for (int i = from; i <= to; i++) {
                        if (isPrime(i)) localCount++;
                    }
                    totalPrimes.addAndGet(localCount);
                    threadTimes[tid]  = System.nanoTime() - tStart;
                    threadCounts[tid] = localCount;
                }));
            }

            // Wait for all threads to finish
            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception e) { e.printStackTrace(); }
            }

            long wallElapsed = System.nanoTime() - wallStart;
            pool.shutdown();

            double wallMs = wallElapsed / 1_000_000.0;

            // Build thread detail JSON array
            StringBuilder threadDetail = new StringBuilder("[");
            for (int t = 0; t < threads; t++) {
                if (t > 0) threadDetail.append(",");
                threadDetail.append(String.format(
                    "{\"id\":%d,\"timeMs\":%.3f,\"primes\":%d}",
                    t, threadTimes[t] / 1_000_000.0, threadCounts[t]
                ));
            }
            threadDetail.append("]");

            String json = String.format(
                "{\"mode\":\"parallel\",\"n\":%d,\"primes\":%d,\"timeMs\":%.3f," +
                "\"threads\":%d,\"threadDetail\":%s," +
                "\"log\":\"Parallel: %d threads counted %d primes in %.3f ms\"}",
                n, totalPrimes.get(), wallMs,
                threads, threadDetail.toString(),
                threads, totalPrimes.get(), wallMs
            );
            sendJson(ex, json);
        }
    }

    // =========================================================================
    // 3. PRODUCER-CONSUMER
    //    A classic bounded-buffer using BlockingQueue.
    //    - 2 Producer threads: generate work items and put() into queue
    //    - 3 Consumer threads: take() items and process them
    //    - Blocking handles synchronization automatically (no busy-wait)
    //    - Results: items produced, items consumed, timing, queue events log
    // =========================================================================
    static class ProducerConsumerHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            int items   = Integer.parseInt(getParam(ex, "items", "30"));
            int bufSize = Integer.parseInt(getParam(ex, "buf",   "10"));

            BlockingQueue<Integer> queue = new ArrayBlockingQueue<>(bufSize);
            AtomicInteger produced = new AtomicInteger(0);
            AtomicInteger consumed = new AtomicInteger(0);
            List<String>  log     = Collections.synchronizedList(new ArrayList<>());

            int numProducers = 2;
            int numConsumers = 3;

            CountDownLatch producersDone = new CountDownLatch(numProducers);
            ExecutorService pool = Executors.newFixedThreadPool(numProducers + numConsumers);

            long start = System.nanoTime();

            // Producers
            int perProducer = items / numProducers;
            for (int p = 0; p < numProducers; p++) {
                final int pid   = p;
                final int count = (p == numProducers-1) ? items - pid*perProducer : perProducer;
                pool.submit(() -> {
                    for (int i = 0; i < count; i++) {
                        int item = produced.incrementAndGet();
                        try {
                            queue.put(item);   // blocks if full — real sync
                            log.add(String.format("P%d → produced item [%d] (queue size=%d)", pid, item, queue.size()));
                        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                    producersDone.countDown();
                });
            }

            // Signal thread: after all producers done, poison pills for consumers
            pool.submit(() -> {
                try {
                    producersDone.await();
                    for (int c = 0; c < numConsumers; c++) queue.put(-1); // poison
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });

            // Consumers
            CountDownLatch consumersDone = new CountDownLatch(numConsumers);
            for (int c = 0; c < numConsumers; c++) {
                final int cid = c;
                pool.submit(() -> {
                    try {
                        while (true) {
                            int item = queue.take();  // blocks if empty — real sync
                            if (item == -1) break;    // poison pill
                            consumed.incrementAndGet();
                            log.add(String.format("C%d ← consumed item [%d]", cid, item));
                        }
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    consumersDone.countDown();
                });
            }

            try { consumersDone.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            long elapsed = System.nanoTime() - start;
            pool.shutdownNow();

            // Build log JSON
            StringBuilder logJson = new StringBuilder("[");
            List<String> capped = log.subList(0, Math.min(log.size(), 40));
            for (int i = 0; i < capped.size(); i++) {
                if (i > 0) logJson.append(",");
                logJson.append("\"").append(capped.get(i).replace("\"","'")).append("\"");
            }
            logJson.append("]");

            String json = String.format(
                "{\"mode\":\"producer-consumer\",\"produced\":%d,\"consumed\":%d," +
                "\"bufferSize\":%d,\"producers\":%d,\"consumers\":%d," +
                "\"timeMs\":%.3f,\"events\":%s}",
                produced.get(), consumed.get(),
                bufSize, numProducers, numConsumers,
                elapsed / 1_000_000.0, logJson
            );
            sendJson(ex, json);
        }
    }

    // =========================================================================
    // 4. SYNC DEMO — Race Condition vs Mutex Lock
    //    Runs T threads doing N increments each on:
    //    a) An UNSAFE plain int  — no synchronization → corrupted result
    //    b) A SAFE synchronized  — mutex lock → always correct
    //    Shows prof exactly why synchronization matters.
    // =========================================================================
    static class SyncDemoHandler implements HttpHandler {
        // Shared unsafe counter (volatile so JIT doesn't optimize away)
        volatile int unsafeCounter = 0;

        public void handle(HttpExchange ex) throws IOException {
            int threads    = Integer.parseInt(getParam(ex, "threads", "4"));
            int increments = Integer.parseInt(getParam(ex, "inc",     "10000"));
            int expected   = threads * increments;

            // ── UNSAFE run ──────────────────────────────────────────────────
            unsafeCounter = 0;
            ExecutorService pool1 = Executors.newFixedThreadPool(threads);
            List<Future<?>> f1 = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                f1.add(pool1.submit(() -> {
                    for (int i = 0; i < increments; i++) {
                        unsafeCounter++;   // READ-MODIFY-WRITE — NOT atomic!
                    }
                }));
            }
            for (Future<?> f : f1) { try { f.get(); } catch (Exception e) {} }
            int unsafeResult = unsafeCounter;
            pool1.shutdown();

            // ── SAFE run with explicit ReentrantLock ─────────────────────────
            final int[] safeCounter = {0};
            ReentrantLock lock = new ReentrantLock();
            ExecutorService pool2 = Executors.newFixedThreadPool(threads);
            List<Future<?>> f2 = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                f2.add(pool2.submit(() -> {
                    for (int i = 0; i < increments; i++) {
                        lock.lock();
                        try { safeCounter[0]++; }  // only one thread at a time
                        finally { lock.unlock(); }
                    }
                }));
            }
            for (Future<?> f : f2) { try { f.get(); } catch (Exception e) {} }
            int safeResult = safeCounter[0];
            pool2.shutdown();

            // ── Also show AtomicInteger approach ────────────────────────────
            AtomicInteger atomicCounter = new AtomicInteger(0);
            ExecutorService pool3 = Executors.newFixedThreadPool(threads);
            List<Future<?>> f3 = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                f3.add(pool3.submit(() -> {
                    for (int i = 0; i < increments; i++) {
                        atomicCounter.incrementAndGet();  // CAS — lock-free
                    }
                }));
            }
            for (Future<?> f : f3) { try { f.get(); } catch (Exception e) {} }
            int atomicResult = atomicCounter.get();
            pool3.shutdown();

            String json = String.format(
                "{\"mode\":\"sync\",\"threads\":%d,\"incrementsPerThread\":%d," +
                "\"expected\":%d,\"unsafeResult\":%d,\"safeResult\":%d,\"atomicResult\":%d," +
                "\"unsafeError\":%d,\"unsafeCorrect\":%s,\"safeCorrect\":%s,\"atomicCorrect\":%s}",
                threads, increments, expected,
                unsafeResult, safeResult, atomicResult,
                Math.abs(expected - unsafeResult),
                unsafeResult == expected ? "true" : "false",
                safeResult   == expected ? "true" : "false",
                atomicResult == expected ? "true" : "false"
            );
            sendJson(ex, json);
        }
    }

    // =========================================================================
    // 5. MATRIX MULTIPLICATION (Parallel)
    //    Multiplies two NxN matrices. Each row of the result is computed by
    //    a separate thread — clean data decomposition, no shared writes.
    // =========================================================================
    static class MatrixHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            int size    = Integer.parseInt(getParam(ex, "size",    "300"));
            int threads = Integer.parseInt(getParam(ex, "threads", "4"));

            int[][] A = randomMatrix(size);
            int[][] B = randomMatrix(size);
            int[][] C = new int[size][size];

            // Sequential
            long seqStart = System.nanoTime();
            multiplySeq(A, B, C, size);
            long seqTime = System.nanoTime() - seqStart;

            // Parallel — divide rows among threads
            int[][] Cp = new int[size][size];
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            long parStart = System.nanoTime();

            int rowsPerThread = size / threads;
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int rowFrom = t * rowsPerThread;
                final int rowTo   = (t == threads-1) ? size : rowFrom + rowsPerThread;
                futures.add(pool.submit(() -> {
                    for (int i = rowFrom; i < rowTo; i++)
                        for (int j = 0; j < size; j++)
                            for (int k = 0; k < size; k++)
                                Cp[i][j] += A[i][k] * B[k][j];
                }));
            }
            for (Future<?> f : futures) { try { f.get(); } catch (Exception e) {} }
            long parTime = System.nanoTime() - parStart;
            pool.shutdown();

            double seqMs   = seqTime / 1_000_000.0;
            double parMs   = parTime / 1_000_000.0;
            double speedup = seqMs / parMs;

            String json = String.format(
                "{\"mode\":\"matrix\",\"size\":%d,\"threads\":%d," +
                "\"seqMs\":%.3f,\"parMs\":%.3f,\"speedup\":%.3f}",
                size, threads, seqMs, parMs, speedup
            );
            sendJson(ex, json);
        }

        void multiplySeq(int[][] A, int[][] B, int[][] C, int n) {
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++)
                    for (int k = 0; k < n; k++)
                        C[i][j] += A[i][k] * B[k][j];
        }

        int[][] randomMatrix(int n) {
            Random r = new Random(42);
            int[][] m = new int[n][n];
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++)
                    m[i][j] = r.nextInt(10);
            return m;
        }
    }

    // ─── Shared prime utility ─────────────────────────────────────────────────
    static boolean isPrime(int n) {
        if (n < 2) return false;
        if (n == 2) return true;
        if (n % 2 == 0) return false;
        for (int i = 3; (long)i*i <= n; i += 2)
            if (n % i == 0) return false;
        return true;
    }
}
