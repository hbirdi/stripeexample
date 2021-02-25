package com.cloudfunction.stripeexample;

import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.PrintWriter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.CustomerCollection;
import com.stripe.model.Price;
import com.stripe.model.PriceCollection;
import com.stripe.model.Product;
import com.stripe.model.ProductCollection;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;

public class ExampleScript implements HttpFunction {

	private static final Gson gson = new Gson();

	@Override
	public void service(HttpRequest request, HttpResponse response) throws IOException {
		// Set Stripe instance
		Stripe.apiKey = "";

		// Default values
		String productName = request.getFirstQueryParameter("productName").orElse("");
		String priceString = request.getFirstQueryParameter("price").orElse("0.00");
		BigDecimal price = new BigDecimal(priceString);
		String name = request.getFirstQueryParameter("name").orElse("");
		String email = request.getFirstQueryParameter("email").orElse("");
		Boolean monthly = Boolean.parseBoolean(request.getFirstQueryParameter("monthly").orElse("false"));

		// Parse JSON request and check for "name" field
		try {
			JsonElement requestParsed = gson.fromJson(request.getReader(), JsonElement.class);
			JsonObject requestJson = null;

			if (requestParsed != null && requestParsed.isJsonObject()) {
				requestJson = requestParsed.getAsJsonObject();
			}

			if (requestJson != null && requestJson.has("productName")) {
				productName = requestJson.get("productName").getAsString();
				priceString = requestJson.get("price").getAsString();
				price = new BigDecimal(priceString);
				name = requestJson.get("name").getAsString();
				email = requestJson.get("email").getAsString();
				monthly = Boolean.parseBoolean(requestJson.get("monthly").getAsString());

			}
		} catch (JsonParseException e) {
			System.out.println("Error parsing JSON: " + e.getMessage());
		}

		// Look up product by product name (Ideally FE can pass back ID)
		String productId = findProductIdByName(productName);

		// Search for matching prices associated with that product
		String priceId = findPriceIdByAmount(price);

		// If no price exists create a new one
		if (priceId.isEmpty()) {
			priceId = createPrice(price, productId, monthly);
		}

		String customerId = "";
		String paymentStatus= "";
		String subscriptionStatus = "";
		// Create the customer
		if (!priceId.isEmpty()) {
			
			//Search for existing customer
			customerId = searchCustomer(email);
			
			//Create new customer if doesnt already exist
			if(customerId.isBlank()) {
				customerId = createCustomer(name, email);
			}

			// Bill the customer via checkout or subscription
			if (monthly) {
				// Bill via subscription
				subscriptionStatus = createSubscription(priceId, customerId);
				System.out.println("subscription status: " + subscriptionStatus);
			} else {
				// Bill via checkout
				paymentStatus = sessionCheckout(priceId);
				System.out.println("payment status: " + paymentStatus);
			}

		} else {
			System.out.println("price not made");
		}

		var writer = new PrintWriter(response.getWriter());
		writer.printf("Payment Status %s!", paymentStatus);
	}

	private String findProductIdByName(String productName) {
		try {
			String productId = "";
			Map<String, Object> productFindParams = new HashMap<>();
			ProductCollection products = Product.list(productFindParams);

			for (Product product : products.getData()) {
				if (product.getName().equalsIgnoreCase(productName)) {
					productId = product.getId();
					System.out.println("Product Id from match: " + productId);
				}
			}
			return productId;

		} catch (StripeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			System.out.println("Error in find product by name ");
			return "";
		}

	}

	private String findPriceIdByAmount(BigDecimal priceAmount) {
		try {
			Map<String, Object> params = new HashMap<>();
			String priceId = "";
			PriceCollection prices = Price.list(params);

			for (Price price : prices.getData()) {
				if (0 == price.getUnitAmountDecimal().compareTo(priceAmount)) {
					priceId = price.getId();
					System.out.println("Price Id from match: " + priceId);
				}
			}

			return priceId;
		} catch (StripeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			System.out.println("Error in find price by amount ");
			return "";
		}
	}

	private String createPrice(BigDecimal priceAmount, String productId, Boolean monthly) {

		Map<String, Object> priceParams = new HashMap<>();
		priceParams.put("unit_amount_decimal", priceAmount);
		priceParams.put("currency", "usd");
		priceParams.put("product", productId);

		if (monthly) {
			Map<String, Object> recurring = new HashMap<>();
			recurring.put("interval", "month");
			priceParams.put("recurring", recurring);
		}

		try {
			Price price = Price.create(priceParams);
			return price.getId();
		} catch (StripeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			System.out.println("Error in create price ");
			return "";
		}
	}
	
	private String searchCustomer(String email) {
		try {
			Map<String, Object> params = new HashMap<>();
			String cusId = "";
			CustomerCollection customers = Customer.list(params);

			for (Customer customer : customers.getData()) {
				if (customer.getEmail().equalsIgnoreCase(email)) {
					cusId = customer.getId();
					System.out.println("Customer Id from match: " + cusId);
				}
			}

			return cusId;
		} catch (StripeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			System.out.println("Error in customer by email ");
			return "";
		}
	}

	private String createCustomer(String name, String email) {
		Map<String, Object> custParams = new HashMap<>();
		custParams.put("email", email);
		custParams.put("name", name);
		System.out.println("name: " + name + " email: " + email);
		try {
			Customer customer = Customer.create(custParams);
			return customer.getId();
		} catch (StripeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			System.out.println("Error in create customer ");
			return "";
		}
	}

	private String sessionCheckout(String priceId) {
		List<Object> paymentMethodTypes = new ArrayList<>();
		paymentMethodTypes.add("card");

		List<Object> lineItems = new ArrayList<>();
		Map<String, Object> lineItem1 = new HashMap<>();

		lineItem1.put("price", priceId);
		lineItem1.put("quantity", 1);

		lineItems.add(lineItem1);

		Map<String, Object> params = new HashMap<>();
		params.put("success_url", "https://example.com/success");
		params.put("cancel_url", "https://example.com/cancel");
		params.put("payment_method_types", paymentMethodTypes);
		params.put("line_items", lineItems);
		params.put("mode", "payment");

		try {
			Session session = Session.create(params);
			return session.getPaymentStatus();
		} catch (StripeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			System.out.println("Error in checkout session ");
			return "";
		}
	}

	private String createSubscription(String priceId, String customerId) {
		List<Object> items = new ArrayList<>();
		Map<String, Object> item1 = new HashMap<>();

		item1.put("price", priceId);
		items.add(item1);

		Map<String, Object> params = new HashMap<>();
		params.put("customer", customerId);
		params.put("items", items);

		try {
			Subscription subscription = Subscription.create(params);
			return subscription.getStatus();
		} catch (StripeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			System.out.println("Error in create subscription ");
			return "";
		}
	}
}