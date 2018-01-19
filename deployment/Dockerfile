FROM zittix/docker-baseimage-java8
MAINTAINER Tom Coupland <tom.coupland@mastodonc.com>

CMD ["/sbin/my_init"]

RUN apt-get update
RUN apt-get install -y \
software-properties-common \
python2.7 \
unzip

ENV JAR_LOCATION=/srv/kixi.mailer.jar
ENV CONFIG_PROFILE=${environment}

ADD target/kixi.mailer-standalone.jar /srv/kixi.mailer.jar
ADD scripts/run.sh /etc/service/kixi.mailer/run

EXPOSE 8080/tcp

RUN apt-get clean && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*
