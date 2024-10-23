package org.tron.core.net.service;

import org.eclipse.osgi.framework.util.ArrayMap;
import org.springframework.stereotype.Service;
import io.github.cdimascio.dotenv.Dotenv;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class EnvService {

	private Map<String, String> envVariables = new HashMap<>();
	private Dotenv dotenv;
	private Dotenv keyenv;

	private static EnvService _instance = null;

	public void init() {

		_instance = this;

		envVariables.put("AMOUNTINLIMIT", "1000");
		envVariables.put("TIMEDIFFLIMIT", "250");
		envVariables.put("BLACKLIST", "[TPsUGKAoXDSFz332ZYtTGdDHWzftLYWFj7,TEtPcNXwPj1PEdsDRCZfUvdFHASrJsFeW5,TN2EQwZpKE5UrShg11kHGyRth7LF5GbRPC,TJf7YitKX2QU5M2kW9hmcdjrAbEz4T5NyQ,TXtARmXejKjroz51YJVkFcdciun8YcU9nn,TLJuomNsHx76vLosaW3Tz3MFTqCANL8v5m,TSMEzJhS5vrWqy9VNLcRjjNuzrMqnRcMbQ,TPrfuW64cDjdC8qYHoujWqy8AbimM5u9bB]");
		envVariables.put("TRXMINAMOUNT", "10");
		envVariables.put("TRXMAXAMOUNT", "10");
		envVariables.put("APPROVED", "[TAt4ufXFaHZAEV44ev7onThjTnF61SEaEM,TCGPc27oyS2x7S5pex7ssyZxZ2edPWonk2,TE2T2vLnEQT1XW647EAQAHWqd6NZL1hweR,TPeoxx1VhUMnAUyjwWfximDYFDQaxNQQ45,TF7ixydn7nfCgj9wQj3fRdKRAvsZ8egHcx,TQzUXP5eXHwrRMou9KYQQq7wEmu8KQF7mX,TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t,TRGEYcmBSAz3PswhtAHcUPjGbuGr1H9Fza,TSig7sWzEL2K83mkJMQtbyPpiVSbR6pZnb,TVXmroHbJsJ6rVm3wGn2G9723yz3Kbqp9x,TWjuiXpamjvm6DeuAUE5vAusQ2QiyQr5JY,TXL6rJbvmjD46zeN1JssfgxvSo99qC8MRT]");
		envVariables.put("COUNT1MIN", "2");
		envVariables.put("COUNT1MAX", "5");
		envVariables.put("COUNT2MIN", "2");
		envVariables.put("COUNT2MAX", "8");

		read();

		startFileWatcher();
	}

	public static EnvService getInstance() {
		return _instance;
	}

	private void loadKey() {
		keyenv = Dotenv.configure().filename(".key").load();

		keyenv.entries().forEach((entry) -> {
			envVariables.put(entry.getKey(), entry.getValue());
		});
	}

	private void loadEnv() {
		dotenv = Dotenv.load();

		ExecuteService.getInstance().clearApiList();

		dotenv.entries().forEach((entry) -> {
			String key = entry.getKey();
			String value = entry.getValue();

			if (key.startsWith("API_") && value.startsWith("http")) {
				String apiName = key.replaceFirst("API_", "");
				ExecuteService.getInstance().updateApi(apiName, value.split(","));
			} else {
				envVariables.put(key, value);
			}
		});
	}

	public void read() {

		loadKey();
		loadEnv();

		System.out.println("Env file loaded");

		ExecuteService.getInstance().notifyEnvChange();
	}

	public String get(String key) {
		return envVariables.get(key);
	}

	// Watch for changes in the .env file and reload when the file is modified
	private void startFileWatcher() {

		String envFileName = dotenv.get("ENV_FILE_NAME", ".env");

		Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(() -> {
			try {
				WatchService watchService = FileSystems.getDefault().newWatchService();
				Path path = Paths.get(System.getProperty("user.dir")); // Directory containing the .env file
				path.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);

				WatchKey key;
				while ((key = watchService.take()) != null) {
					for (WatchEvent<?> event : key.pollEvents()) {
						if (event.context().toString().equals(envFileName)) {
							System.out.println(".env file changed, reloading...");
							loadEnv();
							ExecuteService.getInstance().notifyEnvChange();
						}
						if (event.context().toString().equals(".key")) {
							System.out.println(".key file changed, reloading...");
							loadKey();
							ExecuteService.getInstance().notifyEnvChange();
						}
					}
					key.reset();
				}
			} catch (IOException | InterruptedException e) {
				e.printStackTrace();
			}
		}, 0, 5, TimeUnit.SECONDS); // Checks every 5 seconds; adjust as needed
	}
}
