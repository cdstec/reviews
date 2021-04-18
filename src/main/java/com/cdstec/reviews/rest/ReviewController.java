package com.cdstec.reviews.rest;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.xray.spring.aop.XRayEnabled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.servlet.http.HttpSession;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/")
@XRayEnabled
public class ReviewController {

    @Value("${app.color}")
    private String color;

    @Autowired
    private AmazonDynamoDB amazonDynamoDB;

    //    private final static Boolean ratings_enabled = true;
    private final static Boolean ratings_enabled = Boolean.valueOf(System.getenv("ENABLE_RATINGS"));
    private final static String star_color = System.getenv("STAR_COLOR") == null ? "black" : System.getenv("STAR_COLOR");
    private final static String services_domain = System.getenv("SERVICES_DOMAIN") == null ? "" : ("." + System.getenv("SERVICES_DOMAIN"));
    private final static String ratings_hostname = System.getenv("RATINGS_HOSTNAME") == null ? "ratings" : System.getenv("RATINGS_HOSTNAME");
    private final static String ratings_service = "http://" + ratings_hostname + services_domain + ":9080/ratings";
    // HTTP headers to propagate for distributed tracing are documented at
    // https://istio.io/docs/tasks/telemetry/distributed-tracing/overview/#trace-context-propagation
    private final static String[] headers_to_propagate = {
            // All applications should propagate x-request-id. This header is
            // included in access log statements and is used for consistent trace
            // sampling and log sampling decisions in Istio.
            "x-request-id",

            // Lightstep tracing header. Propagate this if you use lightstep tracing
            // in Istio (see
            // https://istio.io/latest/docs/tasks/observability/distributed-tracing/lightstep/)
            // Note: this should probably be changed to use B3 or W3C TRACE_CONTEXT.
            // Lightstep recommends using B3 or TRACE_CONTEXT and most application
            // libraries from lightstep do not support x-ot-span-context.
            "x-ot-span-context",

            // Datadog tracing header. Propagate these headers if you use Datadog
            // tracing.
            "x-datadog-trace-id",
            "x-datadog-parent-id",
            "x-datadog-sampling-priority",

            // W3C Trace Context. Compatible with OpenCensusAgent and Stackdriver Istio
            // configurations.
            "traceparent",
            "tracestate",

            // Cloud trace context. Compatible with OpenCensusAgent and Stackdriver Istio
            // configurations.
            "x-cloud-trace-context",

            // Grpc binary trace context. Compatible with OpenCensusAgent nad
            // Stackdriver Istio configurations.
            "grpc-trace-bin",

            // b3 trace headers. Compatible with Zipkin, OpenCensusAgent, and
            // Stackdriver Istio configurations. Commented out since they are
            // propagated by the OpenTracing tracer above.
            "x-b3-traceid",
            "x-b3-spanid",
            "x-b3-parentspanid",
            "x-b3-sampled",
            "x-b3-flags",

            // Application-specific headers to forward.
            "end-user",
            "user-agent",
    };


    @GetMapping("/health")
    public Response health() {
        return Response.ok().type(MediaType.APPLICATION_JSON).entity("{\"status\": \"Reviews is healthy\"}").build();
    }

    @ResponseBody
    @GetMapping("/reviews/{productId}")
    public String bookReviewsById(@PathVariable("productId") int productId, @RequestHeader Map<String, String> headers, HttpSession httpsession) {
        int starsReviewer1 = -1;
        int starsReviewer2 = -1;
        if (ratings_enabled) {
            JsonObject ratingsResponse = getRatings(Integer.toString(productId), headers);
            if (ratingsResponse != null) {
                if (ratingsResponse.containsKey("ratings")) {
                    JsonObject ratings = ratingsResponse.getJsonObject("ratings");
                    if (ratings.containsKey("Reviewer1")){
                        starsReviewer1 = ratings.getInt("Reviewer1");
                    }
                    if (ratings.containsKey("Reviewer2")){
                        starsReviewer2 = ratings.getInt("Reviewer2");
                    }
                }
            }
        }

        httpsession.setAttribute("name","=============Redis Session Value TEST================");
        System.out.println(httpsession.getAttribute("name"));

        String jsonResStr = getJsonResponse(Integer.toString(productId), starsReviewer1, starsReviewer2);
        return jsonResStr;
    }

    private JsonObject getRatings(String productId, Map<String, String> requestHeaders) {
        ClientBuilder cb = ClientBuilder.newBuilder();
        Integer timeout = star_color.equals("black") ? 10000 : 2500;
        cb.property("com.ibm.ws.jaxrs.client.connection.timeout", timeout);
        cb.property("com.ibm.ws.jaxrs.client.receive.timeout", timeout);
        Client client = cb.build();
        WebTarget ratingsTarget = client.target(ratings_service + "/" + productId);
        Invocation.Builder builder = ratingsTarget.request(MediaType.APPLICATION_JSON);
        for (String header : headers_to_propagate) {
            String value = requestHeaders.get(header);
            if (value != null) {
                builder.header(header,value);
            }
        }
        try {
            Response r = builder.get();

            int statusCode = r.getStatusInfo().getStatusCode();
            if (statusCode == Response.Status.OK.getStatusCode()) {
                try (StringReader stringReader = new StringReader(r.readEntity(String.class));
                     JsonReader jsonReader = Json.createReader(stringReader)) {
                    return jsonReader.readObject();
                }
            } else {
                System.out.println("Error: unable to contact " + ratings_service + " got status of " + statusCode);
                return null;
            }
        } catch (ProcessingException e) {
            System.err.println("Error: unable to contact " + ratings_service + " got exception " + e);
            return null;
        }
    }

    private String getJsonResponse (String productId, int starsReviewer1, int starsReviewer2) {

        Map<String, AttributeValue> key = new HashMap<>();
        key.put("reviewer", (new AttributeValue()).withS("Reviewer1"));

        GetItemRequest getItemRequest = (new GetItemRequest())
                .withTableName("TBL-REVIEWS")
                .withKey(key);

        String reviews_result = amazonDynamoDB.getItem(getItemRequest)
                .getItem().get("reviews").getS();

        String result = "{";
        result += "\"id\": \"" + productId + "\",";
        result += "\"reviews\": [";

        // reviewer 1:
        result += "{";
        result += "  \"reviewer\": \"Reviewer1\",";
        result += "  \"text\": \"" + reviews_result + "\"";
        if (ratings_enabled) {
            if (starsReviewer1 != -1) {
                if(!color.isEmpty()&& star_color.equals("black") ) {
                    result += ", \"rating\": {\"stars\": " + starsReviewer2 + ", \"color\": \"" + color + "\"}";
                }else
                    result += ", \"rating\": {\"stars\": " + starsReviewer1 + ", \"color\": \"" + star_color + "\"}";
            }
            else {
                result += ", \"rating\": {\"error\": \"Ratings service is currently unavailable\"}";
            }
        }
        result += "},";

        // reviewer 2:
        result += "{";
        result += "  \"reviewer\": \"Reviewer2\",";
        result += "  \"text\": \"Absolutely fun and entertaining. The play lacks thematic depth when compared to other plays by Shakespeare.\"";
        if (ratings_enabled) {
            if (starsReviewer2 != -1) {
                if(!color.isEmpty()&& star_color.equals("black") ) {
                    result += ", \"rating\": {\"stars\": " + starsReviewer2 + ", \"color\": \"" + color + "\"}";
                }else
                    result += ", \"rating\": {\"stars\": " + starsReviewer2 + ", \"color\": \"" + star_color + "\"}";
            }
            else {
                result += ", \"rating\": {\"error\": \"Ratings service is currently unavailable\"}";
            }
        }
        result += "}";

        result += "]";
        result += "}";

        return result;
    }

}