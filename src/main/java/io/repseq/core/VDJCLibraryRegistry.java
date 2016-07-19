package io.repseq.core;

import com.milaboratory.util.GlobalObjectMappers;
import io.repseq.dto.KnownSequenceFragmentData;
import io.repseq.dto.VDJCGeneData;
import io.repseq.dto.VDJCLibraryData;
import io.repseq.seqbase.SequenceAddress;
import io.repseq.seqbase.SequenceResolver;
import io.repseq.seqbase.SequenceResolvers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Registry of VDJCLibraries. Central storage for VDJCLibraries objects. VDJCLibraries can be created only using
 * VDJCLibraryRegistry.
 */
public final class VDJCLibraryRegistry {
    /**
     * If this field is null -> default sequence resolver is used
     */
    final SequenceResolver sequenceResolver;
    /**
     * Resolvers to search for VDJCLibrary with particular name
     */
    final List<LibraryResolver> libraryResolvers = new ArrayList<>();
    /**
     * Collected from all loaded VDJCLibrary
     */
    final Map<String, Long> speciesNames = new HashMap<>();
    /**
     * Loaded libraries
     */
    final Map<SpeciesAndLibraryName, VDJCLibrary> libraries = new HashMap<>();

    /**
     * Creates new VDJCLibraryRegistry with default sequence resolver
     */
    public VDJCLibraryRegistry() {
        this(null);
    }

    /**
     * Creates new VDJCLibraryRegistry with specific sequence resolver.
     *
     * @param sequenceResolver sequece resolver or null to use default resolver
     */
    public VDJCLibraryRegistry(SequenceResolver sequenceResolver) {
        this.sequenceResolver = sequenceResolver;
    }

    /**
     * Return sequence resolver used by this registry
     *
     * @return sequence resolver used by this registry
     */
    public SequenceResolver getSequenceResolver() {
        return sequenceResolver == null ? SequenceResolvers.getDefault() : sequenceResolver;
    }

    /**
     * Returns collection of libraries that are currently loaded by this registry.
     *
     * @return collection of libraries that are currently loaded by this registry
     */
    public Collection<VDJCLibrary> getLoadedLibraries() {
        return libraries.values();
    }

    /**
     * Returns list of libraries that are currently loaded by this registry and has specified name.
     *
     * @return list of libraries that are currently loaded by this registry and has specified name
     */
    public List<VDJCLibrary> getLoadedLibrariesByName(String libraryName) {
        ArrayList<VDJCLibrary> libs = new ArrayList<>();

        for (Map.Entry<SpeciesAndLibraryName, VDJCLibrary> entry : libraries.entrySet())
            if (entry.getKey().getLibraryName().equals(libraryName))
                libs.add(entry.getValue());

        return libs;
    }

    /**
     * Returns list of libraries that are currently loaded by this registry and has specified name pattern.
     *
     * @return list of libraries that are currently loaded by this registry and has specified name pattern
     */
    public List<VDJCLibrary> getLoadedLibrariesByNamePattern(Pattern libraryNamePattern) {
        ArrayList<VDJCLibrary> libs = new ArrayList<>();

        for (Map.Entry<SpeciesAndLibraryName, VDJCLibrary> entry : libraries.entrySet())
            if (libraryNamePattern.matcher(entry.getKey().getLibraryName()).matches())
                libs.add(entry.getValue());

        return libs;
    }

    /**
     * Resolves species name to taxon id
     *
     * @param name species name
     * @return taxon id
     * @throws IllegalArgumentException if can't resolve
     */
    public long resolveSpecies(String name) {
        Long taxonId = speciesNames.get(name);
        if (taxonId == null)
            throw new IllegalArgumentException("Can't resolve species name: " + name);
        return taxonId;
    }

    /**
     * Resolve species name to taxon id
     *
     * @param sal species and library name
     * @return species (taxon-id) and library name
     */
    public SpeciesAndLibraryName resolveSpecies(SpeciesAndLibraryName sal) {
        return sal.bySpeciesName() ?
                new SpeciesAndLibraryName(resolveSpecies(sal.getSpeciesName()),
                        sal.getLibraryName()) :
                sal;
    }

    /**
     * Register library resolver to be used for automatic load of libraries by name and species
     *
     * @param resolver resolver to add
     */
    public void addLibraryResolver(LibraryResolver resolver) {
        libraryResolvers.add(resolver);
    }

    /**
     * Adds path resolver to search for libraries with {libraryName}.json file names in specified folder.
     *
     * @param searchPath path to search for {libraryName}.json files
     */
    public void addSearchPath(Path searchPath) {
        addLibraryResolver(new FolderLibraryResolver(searchPath.toAbsolutePath()));
    }

    /**
     * Returns library with specified name and specified species.
     *
     * If not opened yet, library will be loaded using library providers added to this registry.
     *
     * @param speciesAndLibrary identifier of the library
     * @return library
     * @throws RuntimeException if no library found
     */
    public synchronized VDJCLibrary getLibrary(SpeciesAndLibraryName speciesAndLibrary) {
        // Resolve species name to taxon id if needed
        speciesAndLibrary = resolveSpecies(speciesAndLibrary);

        // Search for already loaded libraries
        VDJCLibrary vdjcLibrary = libraries.get(speciesAndLibrary);

        // If found return it
        if (vdjcLibrary != null)
            return vdjcLibrary;

        // Try load library using provided resolvers
        for (LibraryResolver resolver : libraryResolvers) {
            String libraryName = speciesAndLibrary.getLibraryName();

            // Try resolve
            VDJCLibraryData[] resolved = resolver.resolve(libraryName);

            // If not resolved proceed to next resolver
            if (resolved == null)
                continue;

            // Registering loaded library entries
            for (VDJCLibraryData vdjcLibraryData : resolved) {
                SpeciesAndLibraryName sal = new SpeciesAndLibraryName(vdjcLibraryData.getTaxonId(), libraryName);

                // Check whether library is already loaded manually or using higher priority resolver
                // (or using previous resolution call with the same library name)
                if (libraries.containsKey(sal)) // If so - ignore it
                    continue;

                // Registering library
                registerLibrary(resolver.getContext(libraryName), libraryName, vdjcLibraryData);
            }

            // Check whether required library was loaded
            vdjcLibrary = libraries.get(speciesAndLibrary);

            // If found return it
            if (vdjcLibrary != null)
                return vdjcLibrary;

            // If not - continue
        }

        // If library was not found nor loaded throw exception
        throw new RuntimeException("Can't find library for following species and library name: " + speciesAndLibrary);
    }

    /**
     * Creates and registers single library from VDJCLibraryData
     *
     * @param context context to use for resolution of sequences
     * @param name    library name
     * @param data    library data
     * @return created library
     */
    public synchronized VDJCLibrary registerLibrary(Path context, String name, VDJCLibraryData data) {
        // Creating library object
        VDJCLibrary library = new VDJCLibrary(data, name, this, context);

        // Check if such library is already registered
        if (libraries.containsKey(library.getSpeciesAndLibraryName()))
            throw new RuntimeException("Duplicate library: " + library.getSpeciesAndLibraryName());

        // Loading known sequence fragments from VDJCLibraryData to current SequenceResolver
        SequenceResolver resolver = getSequenceResolver();
        for (KnownSequenceFragmentData fragment : data.getSequenceFragments())
            resolver.resolve(new SequenceAddress(context, fragment.getUri())).setRegion(fragment.getRange(), fragment.getSequence());

        // Adding genes
        for (VDJCGeneData gene : data.getGenes())
            VDJCLibrary.addGene(library, gene);

        // Adding common species names
        Long taxonId = data.getTaxonId();
        for (String s : data.getSpeciesNames())
            if (speciesNames.containsKey(s) && !speciesNames.get(s).equals(taxonId))
                throw new IllegalArgumentException("Mismatch in common species name between several libraries. (Library name = " + name + ").");

        // Adding this library to collection
        libraries.put(library.getSpeciesAndLibraryName(), library);

        return library;
    }

    /**
     * Register libraries from specific file
     *
     * @param file libraries json file
     */
    public void registerLibraries(String file) {
        registerLibraries(Paths.get(file));
    }

    /**
     * Register libraries from specific file with specified name
     *
     * @param file libraries json file
     * @param name library name
     */
    public void registerLibraries(String file, String name) {
        registerLibraries(Paths.get(file), name);
    }

    /**
     * Register libraries from specific file
     *
     * @param file libraries json file
     */
    public void registerLibraries(Path file) {
        String name = file.getFileName().toString();
        name = name.toLowerCase().replaceAll("(?i).json$", "");
        registerLibraries(file, name);
    }

    /**
     * Register libraries from specific file with specified name
     *
     * @param file libraries json file
     * @param name library name
     */
    public void registerLibraries(Path file, String name) {
        file = file.toAbsolutePath();
        try {
            // Getting libraries from file
            VDJCLibraryData[] libraries = GlobalObjectMappers.ONE_LINE.readValue(file.toFile(), VDJCLibraryData[].class);

            // Registering libraries
            for (VDJCLibraryData library : libraries)
                registerLibrary(file.getParent(), name, library);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Default registry
     */
    private static volatile VDJCLibraryRegistry defaultRegistry = new VDJCLibraryRegistry();

    /**
     * Resets default VDJLibrary registry and sets specific sequence resolver
     */
    public static void resetDefaultRegistry(SequenceResolver resolver) {
        defaultRegistry = new VDJCLibraryRegistry(resolver);
    }

    /**
     * Returns default VDJCLibraryRegistry.
     *
     * @return default VDJCLibraryRegistry
     */
    public static VDJCLibraryRegistry getDefault() {
        return defaultRegistry;
    }

    /**
     * Tries to resolve library name to array of VDJCLibraryData[] objects
     *
     * E.g. searches existence of file with {libraryName}.json name in specific folder.
     */
    public interface LibraryResolver {
        VDJCLibraryData[] resolve(String libraryName);

        Path getContext(String libraryName);
    }

    /**
     * Load library data from {libraryName}.json files in specified folder.
     */
    public static final class FolderLibraryResolver implements LibraryResolver {
        final Path path;

        public FolderLibraryResolver(Path path) {
            this.path = path;
        }

        @Override
        public Path getContext(String libraryName) {
            return path;
        }

        @Override
        public VDJCLibraryData[] resolve(String libraryName) {
            try {
                Path filePath = path.resolve(libraryName + ".json");
                if (!Files.exists(filePath))
                    return null;

                // Getting libraries from file
                VDJCLibraryData[] libraries = GlobalObjectMappers.ONE_LINE.readValue(filePath.toFile(), VDJCLibraryData[].class);

                return libraries;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
