/*
 * Copyright 2024 - 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.ai.mcp.sample.server;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class LocationService {

	private static final String NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org";
	private static final String TIMEZONE_BASE_URL = "http://worldtimeapi.org/api";

	private final RestClient restClient;

	public LocationService() {
		this.restClient = RestClient.builder()
				.defaultHeader("Accept", "application/json")
				.defaultHeader("User-Agent", "LocationMCPService/1.0 (spring-ai-mcp)")
				.defaultHeader("Connection", "close") // Prevent HTTP/2 connection reuse issues
				.build();
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GeocodingResult(
			@JsonProperty("lat") String latitude,
			@JsonProperty("lon") String longitude,
			@JsonProperty("display_name") String displayName,
			@JsonProperty("address") Address address
	) {
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Address(
				@JsonProperty("city") String city,
				@JsonProperty("town") String town,
				@JsonProperty("village") String village,
				@JsonProperty("state") String state,
				@JsonProperty("country") String country,
				@JsonProperty("postcode") String postcode
		) {
			public String getCityName() {
				if (city != null) return city;
				if (town != null) return town;
				if (village != null) return village;
				return "Unknown";
			}
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ReverseGeocodingResult(
			@JsonProperty("display_name") String displayName,
			@JsonProperty("address") Address address
	) {
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Address(
				@JsonProperty("city") String city,
				@JsonProperty("town") String town,
				@JsonProperty("village") String village,
				@JsonProperty("state") String state,
				@JsonProperty("country") String country,
				@JsonProperty("postcode") String postcode
		) {
			public String getCityName() {
				if (city != null) return city;
				if (town != null) return town;
				if (village != null) return village;
				return "Unknown";
			}
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TimezoneResult(
			@JsonProperty("timezone") String timezone,
			@JsonProperty("utc_offset") String utcOffset,
			@JsonProperty("datetime") String datetime
	) {}

	/**
	 * Convert address/city name to coordinates
	 * @param address Address or city name to geocode
	 * @return The coordinates and location information for the given address
	 * @throws RestClientException if the request fails
	 */
	@Tool(description = "Convert address or city name to coordinates (latitude, longitude)")
	public String geocodeAddress(String address) {
		// Add retry logic for HTTP/2 connection issues
		int maxRetries = 3;
		Exception lastException = null;

		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			try {
				if (attempt > 1) {
					Thread.sleep(attempt * 500); // Progressive delay between retries
				}

				List<GeocodingResult> results = restClient.get()
						.uri(NOMINATIM_BASE_URL + "/search?q={address}&format=json&limit=1&addressdetails=1", address)
						.retrieve()
						.body(List.class);

				if (results == null || results.isEmpty()) {
					return String.format("No location found for: %s", address);
				}

				// Parse the first result manually since we can't directly deserialize to our record
				var result = (java.util.Map<String, Object>) ((List<?>) results).get(0);
				String lat = (String) result.get("lat");
				String lon = (String) result.get("lon");
				String displayName = (String) result.get("display_name");

				var addressMap = (java.util.Map<String, Object>) result.get("address");
				String city = "Unknown";
				String state = "Unknown";
				String country = "Unknown";

				if (addressMap != null) {
					city = getStringValue(addressMap, "city", "town", "village");
					state = getStringValue(addressMap, "state");
					country = getStringValue(addressMap, "country");
				}

				return String.format("""
					Location: %s
					Coordinates: %s, %s
					City: %s
					State: %s
					Country: %s
					""", displayName, lat, lon, city, state, country);

			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				return String.format("Request interrupted for address: %s", address);
			} catch (Exception e) {
				lastException = e;
				System.err.println("Attempt " + attempt + " failed for geocoding: " + e.getMessage());

				if (attempt < maxRetries) {
					continue;
				}
			}
		}

		return String.format("Failed to geocode address '%s' after %d attempts. Error: %s",
				address, maxRetries, lastException != null ? lastException.getMessage() : "Unknown error");
	}

	/**
	 * Convert coordinates to address/city name
	 * @param latitude Latitude
	 * @param longitude Longitude
	 * @return The address information for the given coordinates
	 * @throws RestClientException if the request fails
	 */
	@Tool(description = "Convert coordinates (latitude, longitude) to address and city name")
	public String reverseGeocode(double latitude, double longitude) {
		// Add retry logic for HTTP/2 connection issues
		int maxRetries = 3;
		Exception lastException = null;

		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			try {
				Thread.sleep(attempt * 500); // Progressive delay between retries

				var result = restClient.get()
						.uri(NOMINATIM_BASE_URL + "/reverse?lat={lat}&lon={lon}&format=json&addressdetails=1",
								latitude, longitude)
						.retrieve()
						.body(java.util.Map.class);

				if (result == null) {
					return String.format("No location found for coordinates: %f, %f", latitude, longitude);
				}

				String displayName = (String) result.get("display_name");
				var addressMap = (java.util.Map<String, Object>) result.get("address");

				String city = "Unknown";
				String state = "Unknown";
				String country = "Unknown";
				String postalCode = "Unknown";

				if (addressMap != null) {
					city = getStringValue(addressMap, "city", "town", "village");
					state = getStringValue(addressMap, "state", "province");
					country = getStringValue(addressMap, "country");
					postalCode = getStringValue(addressMap, "postcode");
				}

				return String.format("""
					Address: %s
					City: %s
					State: %s
					Country: %s
					Postal Code: %s
					""", displayName, city, state, country, postalCode);

			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				return String.format("Request interrupted for coordinates: %.4f, %.4f", latitude, longitude);
			} catch (Exception e) {
				lastException = e;
				System.err.println("Attempt " + attempt + " failed for reverse geocoding: " + e.getMessage());

				if (attempt == maxRetries) {
					// On final failure, try a simpler fallback approach
					return getFallbackLocationInfo(latitude, longitude);
				}
			}
		}

		return String.format("Failed to reverse geocode coordinates: %.4f, %.4f after %d attempts. Error: %s",
				latitude, longitude, maxRetries, lastException != null ? lastException.getMessage() : "Unknown error");
	}

	/**
	 * Get timezone information for coordinates
	 * @param latitude Latitude
	 * @param longitude Longitude
	 * @return The timezone information for the given coordinates
	 * @throws RestClientException if the request fails
	 */
	@Tool(description = "Get timezone information for specific coordinates (latitude, longitude)")
	public String getTimezone(double latitude, double longitude) {
		try {
			// Simple timezone estimation based on longitude
			// For production use, consider using a dedicated timezone API
			int timezoneOffset = (int) Math.round(longitude / 15.0);
			String utcOffset = timezoneOffset >= 0 ? "+" + timezoneOffset : String.valueOf(timezoneOffset);

			return String.format("""
				Coordinates: %.4f, %.4f
				Estimated UTC Offset: %s hours
				Note: This is a rough estimate based on longitude.
				For precise timezone data, use a dedicated timezone service.
				""", latitude, longitude, utcOffset);

		} catch (Exception e) {
			return String.format("Unable to determine timezone for coordinates: %.4f, %.4f. Error: %s",
					latitude, longitude, e.getMessage());
		}
	}

	/**
	 * Get major cities within a country
	 * @param country Country name
	 * @return List of major cities in the country with their coordinates
	 */
	@Tool(description = "Get major cities within a specific country")
	public String getMajorCities(String country) {
		try {
			// Search for the country first to get more specific results
			List<?> results = restClient.get()
					.uri(NOMINATIM_BASE_URL + "/search?q={country}&format=json&limit=5&featureType=city&addressdetails=1",
							country + " major cities")
					.retrieve()
					.body(List.class);

			if (results == null || results.isEmpty()) {
				return String.format("No major cities found for country: %s", country);
			}

			StringBuilder cityList = new StringBuilder();
			cityList.append(String.format("Major cities in %s:\n", country));

			int count = 0;
			for (Object item : results) {
				if (count >= 5) break; // Limit to 5 cities

				var cityMap = (java.util.Map<String, Object>) item;
				String displayName = (String) cityMap.get("display_name");
				String lat = (String) cityMap.get("lat");
				String lon = (String) cityMap.get("lon");

				if (displayName != null && lat != null && lon != null) {
					// Extract city name from display name
					String cityName = displayName.split(",")[0];
					cityList.append(String.format("- %s (%.4f, %.4f)\n",
							cityName, Double.parseDouble(lat), Double.parseDouble(lon)));
					count++;
				}
			}

			return cityList.length() > 0 ? cityList.toString() :
					String.format("No major cities found for country: %s", country);

		} catch (Exception e) {
			throw new RestClientException("Failed to get major cities for country: " + country, e);
		}
	}

	/**
	 * Calculate distance between two points
	 * @param lat1 Latitude of first point
	 * @param lon1 Longitude of first point
	 * @param lat2 Latitude of second point
	 * @param lon2 Longitude of second point
	 * @return Distance between the two points in kilometers
	 */
	@Tool(description = "Calculate distance between two geographic points")
	public String calculateDistance(double lat1, double lon1, double lat2, double lon2) {
		try {
			double earthRadius = 6371; // Earth's radius in kilometers

			double dLat = Math.toRadians(lat2 - lat1);
			double dLon = Math.toRadians(lon2 - lon1);

			double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
					Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
							Math.sin(dLon / 2) * Math.sin(dLon / 2);

			double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
			double distance = earthRadius * c;

			return String.format("""
				Distance between points:
				Point 1: %.4f, %.4f
				Point 2: %.4f, %.4f
				Distance: %.2f kilometers (%.2f miles)
				""", lat1, lon1, lat2, lon2, distance, distance * 0.621371);

		} catch (Exception e) {
			return String.format("Failed to calculate distance: %s", e.getMessage());
		}
	}

	// Helper method to safely get string values from map with fallbacks
	private String getStringValue(java.util.Map<String, Object> map, String... keys) {
		for (String key : keys) {
			Object value = map.get(key);
			if (value instanceof String && !((String) value).isEmpty()) {
				return (String) value;
			}
		}
		return "Unknown";
	}

	// Fallback method for when geocoding services fail
	private String getFallbackLocationInfo(double latitude, double longitude) {
		try {
			// Provide basic geographic information based on coordinates
			String hemisphere = latitude >= 0 ? "Northern" : "Southern";
			String eastWest = longitude >= 0 ? "Eastern" : "Western";

			// Rough continent estimation
			String continent = estimateContinent(latitude, longitude);

			return String.format("""
				Coordinates: %.4f, %.4f
				Hemisphere: %s %s
				Estimated Region: %s
				Note: Detailed address lookup failed, showing approximate location info.
				""", latitude, longitude, hemisphere, eastWest, continent);

		} catch (Exception e) {
			return String.format("Coordinates: %.4f, %.4f (Address lookup unavailable)", latitude, longitude);
		}
	}

	// Simple continent estimation based on coordinates
	private String estimateContinent(double lat, double lon) {
		if (lat >= 35 && lat <= 70 && lon >= -25 && lon <= 45) return "Europe";
		if (lat >= -35 && lat <= 35 && lon >= -20 && lon <= 55) return "Africa";
		if (lat >= 5 && lat <= 55 && lon >= 60 && lon <= 140) return "Asia";
		if (lat >= 10 && lat <= 70 && lon >= -170 && lon <= -50) return "North America";
		if (lat >= -55 && lat <= 15 && lon >= -85 && lon <= -35) return "South America";
		if (lat >= -45 && lat <= -10 && lon >= 110 && lon <= 155) return "Australia/Oceania";
		return "Unknown Region";
	}

	public static void main(String[] args) {
		LocationService service = new LocationService();

		try {
			// Test geocoding
			System.out.println("=== Geocoding Test ===");
			System.out.println(service.geocodeAddress("New York City"));

			// Add delay between requests to be respectful to the API
			Thread.sleep(1000);

			// Test reverse geocoding
			System.out.println("\n=== Reverse Geocoding Test ===");
			System.out.println(service.reverseGeocode(40.7128, -74.0060));

			Thread.sleep(1000);

			// Test timezone
			System.out.println("\n=== Timezone Test ===");
			System.out.println(service.getTimezone(40.7128, -74.0060));

			// Test distance calculation (no external API needed)
			System.out.println("\n=== Distance Test ===");
			System.out.println(service.calculateDistance(40.7128, -74.0060, 34.0522, -118.2437)); // NYC to LA

		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			System.err.println("Test interrupted: " + e.getMessage());
		} catch (Exception e) {
			System.err.println("Test failed: " + e.getMessage());
			e.printStackTrace();
		}
	}
}