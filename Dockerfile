FROM eclipse-temurin:21-jdk-jammy

WORKDIR /usrapp/bin

ENV PORT 35000

COPY /target/classes /usrapp/bin/classes
COPY /target/dependency /usrapp/bin/dependency

CMD ["java","-cp","./classes:./dependency/*","org.example.demo.MicroSpringBoot", "org.example.demo.HelloController", "org.example.demo.GreetingController", "org.example.demo.MongoController"]

