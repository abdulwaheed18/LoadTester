# LoadTester
LoadTester is a Spring Boot app that sends configurable REST requests at a specified TPS and enforces per‐endpoint throttling (e.g., max one request every X ms). Targets (URL, method, headers, payload, TPS, throttle interval) are defined in application.yml. It uses WebFlux’s WebClient for nonblocking calls and Bucket4j for precise rate limiting.
