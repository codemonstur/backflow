import io.undertow.Undertow;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.server.handlers.proxy.ProxyHandler;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;

import static io.undertow.UndertowOptions.ENABLE_HTTP2;
import static io.undertow.server.handlers.ResponseCodeHandler.HANDLE_404;
import static io.undertow.util.Headers.CONTENT_TYPE;
import static io.undertow.util.StatusCodes.OK;
import static java.net.http.HttpClient.newHttpClient;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static org.xnio.Options.BACKLOG;
import static org.xnio.Options.WORKER_TASK_MAX_THREADS;

public class QueryTest {

    public static void main(final String... args) throws IOException, InterruptedException {
        final var server = startHttpServer();
        final var lb = startLoadBalancer();

        server.start();
        lb.start();

        try {
            sendRequest();
        } finally {
            lb.stop();
            server.stop();
        }
    }

    private static Undertow startHttpServer() {
        return Undertow.builder()
            .addHttpListener(8001, "127.0.0.1")
            .setServerOption(ENABLE_HTTP2, true)
            .setHandler(exchange -> {
                exchange.setStatusCode(OK);
                exchange.getResponseHeaders().put(CONTENT_TYPE, "text/plain; charset=UTF-8");
                exchange.getResponseSender().send("Hello, world!");
            })
            .build();
    }

    private static Undertow startLoadBalancer() {
        final int workerThreads = Runtime.getRuntime().availableProcessors() * 8;

        final LoadBalancingProxyClient loadBalancer = new LoadBalancingProxyClient()
            .setConnectionsPerThread(20)
            .addHost(URI.create("http://localhost:8001/"));

        final ProxyHandler proxyHandler = ProxyHandler.builder()
            .setReuseXForwarded(false)
            .setRewriteHostHeader(false)
            .setMaxRequestTime(30_000)
            .setProxyClient(loadBalancer)
            .setNext(HANDLE_404)
            .build();

        return Undertow.builder()
            .setIoThreads(4)
            .setWorkerThreads(workerThreads)
            .setServerOption(ENABLE_HTTP2, true)
            .setWorkerOption(WORKER_TASK_MAX_THREADS, workerThreads)
            .setSocketOption(BACKLOG, 1000)
            .setHandler(proxyHandler)
            .addHttpListener(8000, "0.0.0.0")
            .build();
    }

    private static void sendRequest() throws IOException, InterruptedException {
        final var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:8000"))
            .GET().build();

        final var response = newHttpClient().send(request, ofString());
        
        System.out.println(response.statusCode());
        System.out.println(response.body());
    }

}
