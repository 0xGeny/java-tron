package org.tron.core.net.messagehandler;

import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class MyLogger {

	Logger myLogger = Logger.getLogger("MyLog");
	FileHandler fh;

	private static MyLogger instance;

	public static MyLogger getInstance() {
		if (instance == null) {
			instance = new MyLogger();
			instance.init();
		}

		return instance;
	}

	public void init() {
		try {
			// This block configure the logger with handler and formatter
			fh = new FileHandler("./MyLogFile.log");
			myLogger.addHandler(fh);
			SimpleFormatter formatter = new SimpleFormatter();
			fh.setFormatter(formatter);

			// the following statement is used to log any messages

		} catch (SecurityException | IOException ignored) {
		}
	}

	public static void print(String string) {
		getInstance().myLogger.info(string);
	}
}
