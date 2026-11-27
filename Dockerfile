FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY src ./src
RUN mkdir -p out && javac -d out $(find src -name "*.java")

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/out ./out
COPY static ./static
ENV HOST=0.0.0.0
ENV PORT=8080
EXPOSE 8080
CMD ["java", "-cp", "out", "com.swiftcab.Main"]
