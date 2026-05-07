package com.example.fcfsclaim;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FcfsClaimApplication {

	private static final Logger log = LoggerFactory.getLogger(FcfsClaimApplication.class);

	public static void main(String[] args) {
		SpringApplication.run(FcfsClaimApplication.class, args);
	}

	@EventListener(ApplicationReadyEvent.class)
	public void printJvmInfo() {
		Runtime rt = Runtime.getRuntime();
		long maxHeapMb   = rt.maxMemory()   / 1024 / 1024;
		long totalHeapMb = rt.totalMemory() / 1024 / 1024;
		int  cpus        = rt.availableProcessors();

		log.info("======== JVM 정보 ========");
		log.info("최대 힙 (Xmx)    : {}MB", maxHeapMb);
		log.info("현재 힙 (할당됨) : {}MB", totalHeapMb);
		log.info("인식된 CPU 수    : {}개", cpus);
		log.info("=========================");
	}

}
