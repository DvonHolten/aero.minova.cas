package aero.minova.core.application.system;

import static java.nio.file.Files.isDirectory;

import java.io.File;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class FilesService {

	@Value("${aero_minova_core_application_root_path:../../..}")
	private String rootPath;

	private Path programFilesFolder;
	private Path sharedDataFolder;
	private Path systemFolder;
	private final Logger logger = LoggerFactory.getLogger(FilesService.class);

	public FilesService() {}

	public FilesService(String rootPath) {
		this.rootPath = rootPath;
	}

	/**
	 * Mit {@link Path#toAbsolutePath()} und {@link Path#normalize} werden die Pfade so eindeutig wie möglich.
	 * 
	 * @throws IOException
	 */
	@PostConstruct
	public void setUp() throws IOException {
		if (rootPath == null || rootPath.isEmpty()) {
			rootPath = Paths.get(".").toAbsolutePath().normalize().toString();
		}
		systemFolder = Paths.get(rootPath).toAbsolutePath().normalize();
		sharedDataFolder = systemFolder.resolve("Shared Data").toAbsolutePath().normalize();
		programFilesFolder = sharedDataFolder.resolve("Program Files").toAbsolutePath().normalize();
		if (!isDirectory(systemFolder)) {
			logger.error("msg.SystemFolder %" + systemFolder);
		}
		if (!isDirectory(sharedDataFolder)) {
			logger.error("msg.SharedFolder %" + sharedDataFolder);
		}
		if (!isDirectory(programFilesFolder)) {
			logger.error("msg.ProgramFilesFolder %" + programFilesFolder);
		}
	}

	public Path applicationFolder(String application) {
		return programFilesFolder.resolve(application);
	}

	public Path getSystemFolder() {
		return systemFolder;
	}

	/**
	 * This method populates all the files in a directory to a List
	 * 
	 * @param dir
	 * @throws IOException
	 */
	public List<Path> populateFilesList(Path dir) throws IOException {
		List<Path> filesListInDir = new ArrayList<>();
		File[] files = dir.toFile().listFiles();
		for (File file : files) {
			filesListInDir.add(Paths.get(file.getAbsolutePath()));
			if (file.isDirectory()) {
				filesListInDir.addAll(populateFilesList(file.toPath()));
			}
		}
		return filesListInDir;
	}

	public Path checkLegalPath(String path) throws Exception {
		Path inputPath = getSystemFolder().resolve(path).toAbsolutePath().normalize();
		File f = inputPath.toFile();
		if (!inputPath.startsWith(getSystemFolder())) {
			throw new IllegalAccessException("msg.PathError %" + path + " %" + inputPath);
		}
		if (!f.exists()) {
			throw new NoSuchFileException("msg.FileError %" + path);
		}
		return inputPath;
	}

}
