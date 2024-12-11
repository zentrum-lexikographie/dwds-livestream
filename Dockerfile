FROM node:20 AS builder

RUN apt-get update &&\
    apt-get -y install curl openjdk-17-jdk &&\
    curl -sSL https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh | bash

WORKDIR /build

COPY ./ .

RUN npm install && npx shadow-cljs release app

FROM clojure:temurin-21-jammy

WORKDIR /service

COPY deps.edn .

RUN clojure -P

COPY ./ .

COPY --from=builder /build/public/js ./public/js

ENTRYPOINT ["clojure", "-X",  "dwds.livestream.server/start!"]
