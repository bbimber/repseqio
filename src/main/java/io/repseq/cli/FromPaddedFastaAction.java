package io.repseq.cli;

import cc.redberry.pipe.CUtils;
import com.beust.jcommander.DynamicParameter;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;
import com.milaboratory.cli.Action;
import com.milaboratory.cli.ActionHelper;
import com.milaboratory.cli.ActionParameters;
import com.milaboratory.cli.ActionParametersWithOutput;
import com.milaboratory.core.io.sequence.fasta.FastaReader;
import com.milaboratory.core.io.sequence.fasta.FastaWriter;
import com.milaboratory.core.sequence.NucleotideSequence;
import com.milaboratory.util.GlobalObjectMappers;
import io.repseq.core.*;
import io.repseq.dto.VDJCDataUtils;
import io.repseq.dto.VDJCGeneData;
import io.repseq.dto.VDJCLibraryData;
import io.repseq.util.StringWithMapping;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

public class FromPaddedFastaAction implements Action {
    final Params params = new Params();

    @Override
    public void go(ActionHelper helper) throws Exception {
        Pattern functionalityRegexp = params.getFunctionalityRegexp();
        GeneType geneType = params.getGeneType();

        Map<String, VDJCGeneData> genes = new HashMap<>();

        int importedGenes = 0;

        Path jsonPath = Paths.get(params.getOutputJSON()).toAbsolutePath();
        Path fastaPath = Paths.get(params.getOutputFasta()).toAbsolutePath();
        String relativeFastaPath = jsonPath.getParent().relativize(fastaPath).toString();

        try (FastaReader<?> reader = new FastaReader<>(params.getInput(), null);
             FastaWriter<NucleotideSequence> seqWriter = new FastaWriter<>(params.getOutputFasta())) {
            for (FastaReader.RawFastaRecord record : CUtils.it(reader.asRawRecordsPort())) {
                StringWithMapping swm = StringWithMapping.removeSymbol(record.sequence, params.paddingCharacter);

                NucleotideSequence seq = new NucleotideSequence(swm.getModifiedString());

                if(seq.containsWildcards())
                    continue;

                String[] fields = record.description.split("\\|");

                String geneName = fields[params.nameIndex];

                boolean functionality = true;

                if (params.functionalityIndex != null)
                    functionality = functionalityRegexp.matcher(fields[params.functionalityIndex]).matches();

                SortedMap<ReferencePoint, Long> anchorPoints = new TreeMap<>();

                for (Map.Entry<String, String> p : params.points.entrySet()) {
                    ReferencePoint anchorPoint = ReferencePoint.getPointByName(p.getKey());

                    if (anchorPoint == null)
                        throw new IllegalArgumentException("Unknown anchor point: " + p.getKey());

                    int position = swm.convertPosition(Integer.decode(p.getValue()));

                    if (position == -1)
                        continue;

                    anchorPoints.put(anchorPoint, (long) position);
                }

                VDJCGeneData gene = new VDJCGeneData(new BaseSequence("file://" + relativeFastaPath + "#" + geneName),
                        geneName, geneType, functionality, new Chains(params.chain), anchorPoints);

                if (genes.containsKey(geneName)) {
                    if (params.getIgnoreDuplicates())
                        continue;
                    else
                        throw new IllegalArgumentException("Duplicate records for " + geneName);
                }

                seqWriter.write(record.description, seq);

                genes.put(geneName, gene);
            }
        }

        VDJCLibraryData library = new VDJCLibraryData(params.taxonId, Collections.EMPTY_LIST, new ArrayList<>(genes.values()), Collections.EMPTY_LIST);

        VDJCDataUtils.sort(library);

        GlobalObjectMappers.PRETTY.writeValue(new File(params.getOutputJSON()), new VDJCLibraryData[]{library});
    }

    @Override
    public String command() {
        return "fromPaddedFasta";
    }

    @Override
    public ActionParameters params() {
        return params;
    }

    @Parameters(commandDescription = "Converts library from padded fasta file (IMGT-like) to non-padded fasta and " +
            "json library files. Json library contain links to non-padded fasta file, so to use library one need both " +
            "output file, or library can be compiled using 'repseqio compile'.")
    public static final class Params extends ActionParametersWithOutput {
        @Parameter(description = "input_padded.fasta output.fasta output.json", arity = 2)
        public List<String> parameters;

        @Parameter(description = "Padding character",
                names = {"-p", "--padding-character"})
        public char paddingCharacter = '.';

        @Parameter(description = "Gene type (V/D/J/C)",
                names = {"-g", "--gene-type"},
                required = true)
        public String geneType;

        @Parameter(description = "Ignore duplicate genes",
                names = {"-i", "--ignore-duplicates"})
        public Boolean ignoreDuplicates;

        @Parameter(description = "Gene name index (0-based) in FASTA description line (e.g. 1 for IMGT files).",
                names = {"-n", "--name-index"},
                required = true)
        public int nameIndex;

        @Parameter(description = "Functionality mark index (0-based) in FASTA description line (e.g. 3 for IMGT files).",
                names = {"-j", "--functionality-index"})
        public Integer functionalityIndex;

        @Parameter(description = "Functionality regexp.",
                names = {"--functionality-regexp"})
        public String functionalityRegexp = ".*[Ff].*";

        @Parameter(description = "Chain.",
                names = {"-c", "--chain"},
                required = true)
        public String chain;

        @Parameter(description = "Taxon id",
                names = {"-t", "--taxon-id"},
                required = true)
        public Long taxonId;

        @DynamicParameter(names = "-P", description = "Positions of anchor points in padded file. To define position " +
                "relative to еру end of sequence use negative values: -1 = sequence end, -2 = last but one letter. " +
                "Example: -PFR1Begin=0 -PVEnd=-1")
        public Map<String, String> points = new HashMap<>();

        public boolean getIgnoreDuplicates() {
            return ignoreDuplicates != null && ignoreDuplicates;
        }

        public String getInput() {
            return parameters.get(0);
        }

        public String getOutputFasta() {
            return parameters.get(1);
        }

        public String getOutputJSON() {
            return parameters.get(2);
        }

        public GeneType getGeneType() {
            return GeneType.fromChar(geneType.charAt(0));
        }

        public Pattern getFunctionalityRegexp() {
            return Pattern.compile(functionalityRegexp);
        }

        @Override
        protected List<String> getOutputFiles() {
            return parameters.subList(1, 3);
        }

        @Override
        public void validate() {
            if (parameters.size() != 3)
                throw new ParameterException("Wrong number of arguments.");
        }
    }
}