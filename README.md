# LoadTester

A Spring Boot–based load‐testing utility that can:

- **Emit high‐volume REST requests** (up to hundreds of TPS per target).
- **Enforce per‐endpoint throttling** (e.g. limit to at most 1 request every X ms).
- Fully **configure URLs, HTTP method, headers, payloads, desired TPS, and throttle intervals** in `application.yml`.
- Use non‐blocking requests via Spring WebFlux’s `WebClient`.
- Leverage [Bucket4j](https://github.com/bucket4j/bucket4j) for precise token‐bucket rate limits.
- Provide logs of request success/failure and skip events when throttled.

---

## Table of Contents

1. [Features](#features)  
2. [Project Structure](#project-structure)  
3. [Prerequisites](#prerequisites)  
4. [Configuration](#configuration)  
5. [Building the Project](#building-the-project)  
6. [Running LoadTester](#running-loadtester)  
7. [How It Works](#how-it-works)  
8. [Customizing / Extending](#customizing--extending)  
9. [Logging & Metrics](#logging--metrics)  
10. [License](#license)

---

## Features

- **High‐TPS Generation**  
  Spin up concurrent request “emitters” that target one or more REST endpoints at a configurable rate (e.g. 300 TPS).

- **Per‐Endpoint Throttling**  
  For each endpoint, specify a “throttle interval” in milliseconds so that if you must never send more than one request every _N_ ms, Bucket4j enforces that automatically.

- **Non‐Blocking HTTP Client**  
  Uses Reactor’s `Flux.interval(...)` to schedule ticks and Spring WebFlux’s `WebClient` to send requests without blocking threads.

- **Externalized Configuration**  
  All endpoint details (URL, method, headers, payload path, desired TPS, throttle interval) live in `application.yml`. No hard‐coding.

- **Easy to Extend**  
  Simple service classes (`RateLimiterService`, `LoadEmitterService`, `PayloadLoader`) can be modified to add new behavior—e.g. dynamic reloading, circuit breakers, custom metrics.

---

## Project Structure

``` text
loadtester/
├─ pom.xml
├─ README.md
├─ src
│  ├─ main
│  │  ├─ java
│  │  │  └─ com/example/loadtester
│  │  │     ├─ config
│  │  │     │  └─ LoadTesterProperties.java
│  │  │     ├─ service
│  │  │     │  ├─ LoadEmitterService.java
│  │  │     │  ├─ RateLimiterService.java
│  │  │     │  └─ PayloadLoader.java
│  │  │     └─ LoadTesterApplication.java
│  │  └─ resources
│  │     ├─ application.yml
│  │     └─ payloads
│  │        └─ foo.json
│  └─ test
│     └─ java (optional—add unit tests here)
└─ loadtester.zip (if you distribute a ZIP)

```

---

## Prerequisites

LoadTester requires the following tools to build and run:

- **Java 17** – set `JAVA_HOME` to a JDK 17 installation.
- **Maven 3.8+** – used for building the Spring Boot application.

---

## Configuration

All behavior is driven from `src/main/resources/application.yml`. Each entry
under `loadtester.targets` describes an endpoint to exercise:

| Field | Description |
|-------|-------------|
| `name` | Logical name for the target; used in logs. |
| `url` | Full URL to invoke. |
| `method` | HTTP method (e.g. `GET`, `POST`). |
| `headers` | Optional map of request headers. |
| `payloadPath` | Resource location of the request body (for POST/PUT). |
| `desiredTps` | Target requests per second for this endpoint. |
| `throttleIntervalMs` | Minimum interval (ms) between requests. |

Refer to the example `application.yml` for a sample configuration.

---

## Building the Project

From the project root run:

```bash
mvn package
```

The built JAR will be located in `target/`, e.g.
`target/loadtester-0.0.1-SNAPSHOT.jar`.

---

## Running LoadTester

After building, execute the JAR with Java:

```bash
java -jar target/loadtester-0.0.1-SNAPSHOT.jar
```

You can override the configuration file via Spring Boot’s standard mechanisms,
for example:

```bash
java -jar target/loadtester-0.0.1-SNAPSHOT.jar \
  --spring.config.location=classpath:/application.yml
```

---
