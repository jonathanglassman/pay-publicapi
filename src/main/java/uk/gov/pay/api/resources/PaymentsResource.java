package uk.gov.pay.api.resources;

import black.door.hate.HalRepresentation;
import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dropwizard.auth.Auth;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.pay.api.app.config.PublicApiConfig;
import uk.gov.pay.api.auth.Account;
import uk.gov.pay.api.exception.CancelChargeException;
import uk.gov.pay.api.exception.GetChargeException;
import uk.gov.pay.api.exception.GetEventsException;
import uk.gov.pay.api.exception.SearchChargesException;
import uk.gov.pay.api.model.ChargeFromResponse;
import uk.gov.pay.api.model.CreatePaymentRequest;
import uk.gov.pay.api.model.PaymentError;
import uk.gov.pay.api.model.PaymentEvents;
import uk.gov.pay.api.model.PaymentForSearchResult;
import uk.gov.pay.api.model.PaymentSearchResponse;
import uk.gov.pay.api.model.PaymentSearchResults;
import uk.gov.pay.api.model.links.PaymentWithAllLinks;
import uk.gov.pay.api.service.ConnectorUriGenerator;
import uk.gov.pay.api.service.CreatePaymentService;
import uk.gov.pay.api.service.PublicApiUriGenerator;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.http.HttpStatus.SC_OK;
import static uk.gov.pay.api.validation.PaymentSearchValidator.validateSearchParameters;

@Path("/")
@Api(value = "/", description = "Public Api Endpoints")
@Produces({"application/json"})
public class PaymentsResource {

    private static final Logger logger = LoggerFactory.getLogger(PaymentsResource.class);

    private final String baseUrl;

    private final Client client;
    private final CreatePaymentService createPaymentService;
    private final ObjectMapper objectMapper;
    private final PublicApiUriGenerator publicApiUriGenerator;
    private final ConnectorUriGenerator connectorUriGenerator;

    @Inject
    public PaymentsResource(Client client, CreatePaymentService createPaymentService,
                            ObjectMapper objectMapper, PublicApiConfig configuration,
                            PublicApiUriGenerator publicApiUriGenerator, ConnectorUriGenerator connectorUriGenerator) {
        this.client = client;
        this.createPaymentService = createPaymentService;
        this.baseUrl = configuration.getBaseUrl();
        this.objectMapper = objectMapper;
        this.publicApiUriGenerator = publicApiUriGenerator;
        this.connectorUriGenerator = connectorUriGenerator;
    }

    @GET
    @Timed
    @Path("/v1/payments/{paymentId}")
    @Produces(APPLICATION_JSON)
    @ApiOperation(
            value = "Find payment by ID",
            notes = "Return information about the payment " +
                    "The Authorisation token needs to be specified in the 'authorization' header " +
                    "as 'authorization: Bearer YOUR_API_KEY_HERE'",
            code = 200)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "OK", response = PaymentWithAllLinks.class),
            @ApiResponse(code = 401, message = "Credentials are required to access this resource"),
            @ApiResponse(code = 404, message = "Not found", response = PaymentError.class),
            @ApiResponse(code = 500, message = "Downstream system error", response = PaymentError.class)})
    public Response getPayment(@ApiParam(value = "accountId", hidden = true) @Auth Account account,
                               @PathParam("paymentId") String paymentId) {

        logger.info("Payment request - paymentId={}", paymentId);
        
        Response connectorResponse = client
                .target(connectorUriGenerator.chargeURI(account, paymentId))
                .request()
                .get();

        if (connectorResponse.getStatus() == SC_OK) {
            ChargeFromResponse chargeFromResponse = connectorResponse.readEntity(ChargeFromResponse.class);
            URI paymentURI = publicApiUriGenerator.getPaymentURI(chargeFromResponse.getChargeId());

            PaymentWithAllLinks payment = PaymentWithAllLinks.getPaymentWithLinks(
                    account.getPaymentType(),
                    chargeFromResponse,
                    paymentURI,
                    publicApiUriGenerator.getPaymentEventsURI(chargeFromResponse.getChargeId()),
                    publicApiUriGenerator.getPaymentCancelURI(chargeFromResponse.getChargeId()),
                    publicApiUriGenerator.getPaymentRefundsURI(chargeFromResponse.getChargeId()));

            logger.info("Payment returned - [ {} ]", payment);
            return Response.ok(payment).build();
        }
        throw new GetChargeException(connectorResponse);
    }

    @GET
    @Timed
    @Path("/v1/payments/{paymentId}/events")
    @Produces(APPLICATION_JSON)
    @ApiOperation(
            value = "Return payment events by ID",
            notes = "Return payment events information about a certain payment " +
                    "The Authorisation token needs to be specified in the 'authorization' header " +
                    "as 'authorization: Bearer YOUR_API_KEY_HERE'",
            code = 200)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "OK", response = PaymentEvents.class),
            @ApiResponse(code = 401, message = "Credentials are required to access this resource"),
            @ApiResponse(code = 404, message = "Not found", response = PaymentError.class),
            @ApiResponse(code = 500, message = "Downstream system error", response = PaymentError.class)})
    public Response getPaymentEvents(@ApiParam(value = "accountId", hidden = true) @Auth Account account,
                                     @PathParam("paymentId") String paymentId) {

        logger.info("Payment events request - payment_id={}", paymentId);

        Response connectorResponse = client
                .target(connectorUriGenerator.chargeEventsURI(account, paymentId))
                .request()
                .get();

        if (connectorResponse.getStatus() == SC_OK) {

            JsonNode payload = connectorResponse.readEntity(JsonNode.class);
            URI paymentEventsLink = publicApiUriGenerator.getPaymentEventsURI(payload.get("charge_id").asText());

            URI paymentLink = publicApiUriGenerator.getPaymentURI(payload.get("charge_id").asText());

            PaymentEvents response =
                    PaymentEvents.createPaymentEventsResponse(payload, paymentLink.toString())
                            .withSelfLink(paymentEventsLink.toString());

            logger.info("Payment events returned - [ {} ]", response);

            return Response.ok(response).build();
        }

        throw new GetEventsException(connectorResponse);
    }

    @GET
    @Timed
    @Path("/v1/payments")
    @Produces(APPLICATION_JSON)
    @ApiOperation(
            value = "Search payments",
            notes = "Search payments by reference, state, 'from' and 'to' date. " +
                    "The Authorisation token needs to be specified in the 'authorization' header " +
                    "as 'authorization: Bearer YOUR_API_KEY_HERE'",
            responseContainer = "List",
            code = 200)

    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "OK", response = PaymentSearchResults.class),
            @ApiResponse(code = 401, message = "Credentials are required to access this resource"),
            @ApiResponse(code = 422, message = "Invalid parameters: from_date, to_date, status, display_size. See Public API documentation for the correct data formats", response = PaymentError.class),
            @ApiResponse(code = 500, message = "Downstream system error", response = PaymentError.class)})
    public Response searchPayments(@ApiParam(value = "accountId", hidden = true)
                                   @Auth Account account,
                                   @ApiParam(value = "Your payment reference to search", hidden = false)
                                   @QueryParam("reference") String reference,
                                   @ApiParam(value = "The user email used in the payment to be searched", hidden = false)
                                   @QueryParam("email") String email,
                                   @ApiParam(value = "State of payments to be searched. Example=success", hidden = false, allowableValues = "range[created,started,submitted,success,failed,cancelled,error")
                                   @QueryParam("state") String state,
                                   @ApiParam(value = "Card brand used for payment. Example=master-card", hidden = false)
                                   @QueryParam("card_brand") String cardBrand,
                                   @ApiParam(value = "From date of payments to be searched (this date is inclusive). Example=2015-08-13T12:35:00Z", hidden = false)
                                   @QueryParam("from_date") String fromDate,
                                   @ApiParam(value = "To date of payments to be searched (this date is exclusive). Example=2015-08-14T12:35:00Z", hidden = false)
                                   @QueryParam("to_date") String toDate,
                                   @ApiParam(value = "Page number requested for the search, should be a positive integer (optional, defaults to 1)", hidden = false)
                                   @QueryParam("page") String pageNumber,
                                   @ApiParam(value = "Number of results to be shown per page, should be a positive integer (optional, defaults to 500, max 500)", hidden = false)
                                   @QueryParam("display_size") String displaySize,
                                   @Context UriInfo uriInfo) {

        logger.info("Payments search request - [ {} ]",
                format("reference:%s, email: %s, status: %s, card_brand %s, fromDate: %s, toDate: %s, page: %s, display_size: %s",
                        reference, email, state, cardBrand, fromDate, toDate, pageNumber, displaySize));

        validateSearchParameters(state, reference, email, cardBrand, fromDate, toDate, pageNumber, displaySize);

        if (isNotBlank(cardBrand)) {
            cardBrand = cardBrand.toLowerCase();
        }

        List<Pair<String, String>> queryParams = asList(
                Pair.of("reference", reference),
                Pair.of("email", email),
                Pair.of("state", state),
                Pair.of("card_brand", cardBrand),
                Pair.of("from_date", fromDate),
                Pair.of("to_date", toDate),
                Pair.of("transactionType", "charge"),
                Pair.of("page", pageNumber),
                Pair.of("display_size", displaySize)
        );
        
        Response connectorResponse = client
                .target(connectorUriGenerator.chargesURI(account, queryParams))
                .request()
                .header(HttpHeaders.ACCEPT, APPLICATION_JSON)
                .get();

        logger.info("response from connector form charge search: " + connectorResponse);

        if (connectorResponse.getStatus() == SC_OK) {
            try {
                JsonNode responseJson = connectorResponse.readEntity(JsonNode.class);
                logger.debug("json response from connector from charge search: " + responseJson);

                TypeReference<PaymentSearchResponse> typeRef = new TypeReference<PaymentSearchResponse>() {};
                PaymentSearchResponse searchResponse = objectMapper.readValue(responseJson.traverse(), typeRef);
                List<PaymentForSearchResult> paymentsForSearchResults = searchResponse.getPayments()
                        .stream()
                        .map(charge -> PaymentForSearchResult.valueOf(
                                charge,
                                publicApiUriGenerator.getPaymentURI(charge.getChargeId()),
                                publicApiUriGenerator.getPaymentEventsURI(charge.getChargeId()),
                                publicApiUriGenerator.getPaymentCancelURI(charge.getChargeId()),
                                publicApiUriGenerator.getPaymentRefundsURI(charge.getChargeId())))
                        .collect(Collectors.toList());

                HalRepresentation.HalRepresentationBuilder halRepresentation = HalRepresentation.builder()
                        .addProperty("results", paymentsForSearchResults)
                        .addProperty("count", searchResponse.getCount())
                        .addProperty("total", searchResponse.getTotal())
                        .addProperty("page", searchResponse.getPage());

                addLink(halRepresentation, "self", transformIntoPublicUri(baseUrl, searchResponse.getLinks().getSelf()));
                addLink(halRepresentation, "first_page", transformIntoPublicUri(baseUrl, searchResponse.getLinks().getFirstPage()));
                addLink(halRepresentation, "last_page", transformIntoPublicUri(baseUrl, searchResponse.getLinks().getLastPage()));
                addLink(halRepresentation, "prev_page", transformIntoPublicUri(baseUrl, searchResponse.getLinks().getPrevPage()));
                addLink(halRepresentation, "next_page", transformIntoPublicUri(baseUrl, searchResponse.getLinks().getNextPage()));

                return Response.ok(halRepresentation.build().toString()).build();
            } catch (IOException | ProcessingException | URISyntaxException e) {
                throw new SearchChargesException(e);
            }
        }
        throw new SearchChargesException(connectorResponse);
    }

    private void addLink(HalRepresentation.HalRepresentationBuilder halRepresentationBuilder, String name, URI uri) {
        if (uri != null) {
            halRepresentationBuilder.addLink(name, uri);
        }
    }

    private URI transformIntoPublicUri(String baseUrl, uk.gov.pay.api.model.links.Link link) throws URISyntaxException {
        if (link == null)
            return null;

        return UriBuilder.fromUri(baseUrl)
                .path("/v1/payments")
                .replaceQuery(new URI(link.getHref()).getQuery())
                .build();
    }

    @POST
    @Timed
    @Path("/v1/payments")
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    @ApiOperation(
            value = "Create new payment",
            notes = "Create a new payment for the account associated to the Authorisation token. " +
                    "The Authorisation token needs to be specified in the 'authorization' header " +
                    "as 'authorization: Bearer YOUR_API_KEY_HERE'",
            code = 201,
            nickname = "newPayment")
    @ApiResponses(value = {
            @ApiResponse(code = 201, message = "Created", response = PaymentWithAllLinks.class),
            @ApiResponse(code = 400, message = "Bad request", response = PaymentError.class),
            @ApiResponse(code = 401, message = "Credentials are required to access this resource"),
            @ApiResponse(code = 422, message = "Invalid attribute value: description. Must be less than or equal to 255 characters length", response = PaymentError.class),
            @ApiResponse(code = 500, message = "Downstream system error", response = PaymentError.class)})
    public Response createNewPayment(@ApiParam(value = "accountId", hidden = true) @Auth Account account,
                                     @ApiParam(value = "requestPayload", required = true) CreatePaymentRequest requestPayload) {
        logger.info("Payment create request - [ {} ]", requestPayload);

        PaymentWithAllLinks createdPayment = createPaymentService.create(account, requestPayload);

        Response response = Response
                .created(publicApiUriGenerator.getPaymentURI(createdPayment.getPayment().getPaymentId()))
                .entity(createdPayment)
                .build();

        logger.info("Payment returned (created): [ {} ]", createdPayment);
        return response;
    }

    @POST
    @Timed
    @Path("/v1/payments/{paymentId}/cancel")
    @Produces(APPLICATION_JSON)
    @ApiOperation(
            value = "Cancel payment",
            notes = "Cancel a payment based on the provided payment ID and the Authorisation token. " +
                    "The Authorisation token needs to be specified in the 'authorization' header " +
                    "as 'authorization: Bearer YOUR_API_KEY_HERE'. A payment can only be cancelled if it's in " +
                    "a state that isn't finished.",
            code = 204)
    @ApiResponses(value = {
            @ApiResponse(code = 204, message = "No Content"),
            @ApiResponse(code = 400, message = "Cancellation of payment failed", response = PaymentError.class),
            @ApiResponse(code = 401, message = "Credentials are required to access this resource"),
            @ApiResponse(code = 404, message = "Not found", response = PaymentError.class),
            @ApiResponse(code = 409, message = "Conflict", response = PaymentError.class),
            @ApiResponse(code = 500, message = "Downstream system error", response = PaymentError.class)
    })
    public Response cancelPayment(@ApiParam(value = "accountId", hidden = true) @Auth Account account,
                                  @PathParam("paymentId") String paymentId) {

        logger.info("Payment cancel request - payment_id=[{}]", paymentId);
        
        Response connectorResponse = client
                .target(connectorUriGenerator.cancelURI(account, paymentId))
                .request()
                .post(Entity.json("{}"));

        if (connectorResponse.getStatus() == HttpStatus.SC_NO_CONTENT) {
            connectorResponse.close();
            return Response.noContent().build();
        }

        throw new CancelChargeException(connectorResponse);
    }
}
