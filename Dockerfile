FROM openjdk:17

WORKDIR /app

COPY . .

RUN javac BenchmarkServer.java

EXPOSE 8080

CMD ["java", "BenchmarkServer"]