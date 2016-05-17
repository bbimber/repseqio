/*
 * Copyright (c) 2014-2015, Bolotin Dmitry, Chudakov Dmitry, Shugay Mikhail
 * (here and after addressed as Inventors)
 * All Rights Reserved
 *
 * Permission to use, copy, modify and distribute any part of this program for
 * educational, research and non-profit purposes, by non-profit institutions
 * only, without fee, and without a written agreement is hereby granted,
 * provided that the above copyright notice, this paragraph and the following
 * three paragraphs appear in all copies.
 *
 * Those desiring to incorporate this work into commercial products or use for
 * commercial purposes should contact the Inventors using one of the following
 * email addresses: chudakovdm@mail.ru, chudakovdm@gmail.com
 *
 * IN NO EVENT SHALL THE INVENTORS BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
 * SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS,
 * ARISING OUT OF THE USE OF THIS SOFTWARE, EVEN IF THE INVENTORS HAS BEEN
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * THE SOFTWARE PROVIDED HEREIN IS ON AN "AS IS" BASIS, AND THE INVENTORS HAS
 * NO OBLIGATION TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR
 * MODIFICATIONS. THE INVENTORS MAKES NO REPRESENTATIONS AND EXTENDS NO
 * WARRANTIES OF ANY KIND, EITHER IMPLIED OR EXPRESS, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY OR FITNESS FOR A
 * PARTICULAR PURPOSE, OR THAT THE USE OF THE SOFTWARE WILL NOT INFRINGE ANY
 * PATENT, TRADEMARK OR OTHER RIGHTS.
 */
package io.repseq.reference;

import com.milaboratory.core.mutations.Mutations;
import com.milaboratory.core.sequence.NucleotideSequence;
import com.milaboratory.core.sequence.SequencesUtils;
import com.milaboratory.util.Bit2Array;
import com.milaboratory.util.CountingInputStream;

import java.io.*;
import java.util.*;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;


public class LociLibraryReader {
    final boolean withFR4Correction;
    final CountingInputStream countingInputStream;
    final DataInputStream stream;
    final LociLibrary library = new LociLibrary();
    long beginOfCurrentBlock = 0;
    LociLibraryReaderListener listener = new LociLibraryReaderListener();
    LocusContainer container;
    EnumMap<GeneType, List<Gene>> genes;
    List<Gene> allGenes = null;
    EnumMap<GeneType, List<Allele>> alleles;
    Map<String, Gene> nameToGenes;
    Map<String, Allele> nameToAlleles;

    LociLibraryReader(InputStream stream, boolean withFR4Correction) {
        this.countingInputStream = new CountingInputStream(stream);
        this.stream = new DataInputStream(countingInputStream);
        this.withFR4Correction = withFR4Correction;
    }

    public static LociLibrary read(File file, boolean withFR4Correction) throws IOException {
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
            return read(bis, withFR4Correction);
        }
    }

    public static LociLibrary read(String fileName, boolean withFR4Correction) throws IOException {
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(fileName))) {
            return read(bis, withFR4Correction);
        }
    }

    public static LociLibrary read(InputStream stream, boolean withFR4Correction) throws IOException {
        LociLibraryReader reader = new LociLibraryReader(stream, withFR4Correction);
        reader.checkMagic();
        reader.readToEnd();
        return reader.library;
    }

    public LociLibraryReader setListener(LociLibraryReaderListener listener) {
        this.listener = listener;
        return this;
    }

    void checkMagic() throws IOException {
        if (stream.readInt() != LociLibraryWriter.MAGIC)
            throw new IOException("Wrong magic bytes.");
        listener.magic(beginOfCurrentBlock, countingInputStream.getBytesRead());
    }

    void readToEnd() throws IOException {
        int b;

        while ((b = stream.read()) != -1) {
            // Saving current block position in stream
            // -1 for b
            beginOfCurrentBlock = countingInputStream.getBytesRead() - 1;

            // Selecting proper method for reading certain block type
            switch ((byte) b) {
                case LociLibraryWriter.MAGIC_TYPE:
                    skipMagic();
                    break;
                case LociLibraryWriter.META_TYPE:
                    readMeta();
                    break;
                case LociLibraryWriter.SEQUENCE_PART_ENTRY_TYPE:
                    readSequencePart(false);
                    break;
                case LociLibraryWriter.SEQUENCE_PART_ENTRY_TYPE_COMPRESSED:
                    readSequencePart(true);
                    break;
                case LociLibraryWriter.LOCUS_BEGIN_TYPE:
                    beginLocus();
                    break;
                case LociLibraryWriter.ALLELE_TYPE:
                    readAllele();
                    break;
                case LociLibraryWriter.LOCUS_END_TYPE:
                    endLocus();
                    break;
                case LociLibraryWriter.SPECIES_NAME_TYPE:
                    readSpeciesName();
                    break;
                default:
                    throw new IOException("Unknown type of record: " + b);
            }
        }

        if (container != null)
            throw new IOException("Premature end of stream.");
    }

    private void skipMagic() throws IOException {
        stream.readByte();
        stream.readByte();
        stream.readByte();
        listener.magic(beginOfCurrentBlock, countingInputStream.getBytesRead());
    }

    private void beginLocus() throws IOException {
        String locusId = stream.readUTF();
        Chain chain = Chain.fromId(locusId);
        if (chain == null)
            throw new IOException("Unknown chain: " + locusId);
        int taxonId = stream.readInt();
        long lsb = stream.readLong();
        long msb = stream.readLong();
        UUID uuid = new UUID(msb, lsb);

        genes = new EnumMap<>(GeneType.class);
        for (GeneType gt : GeneType.values())
            genes.put(gt, new ArrayList<Gene>());

        alleles = new EnumMap<>(GeneType.class);
        for (GeneType gt : GeneType.values())
            alleles.put(gt, new ArrayList<Allele>());

        container = new LocusContainer(uuid, new SpeciesAndChain(taxonId, chain), genes, alleles,
                Collections.unmodifiableMap(nameToGenes = new HashMap<>()),
                Collections.unmodifiableMap(nameToAlleles = new HashMap<>()),
                Collections.unmodifiableList(allGenes = new ArrayList<>()));

        container.setLibrary(library);

        listener.beginLocus(beginOfCurrentBlock, countingInputStream.getBytesRead(), container);
    }

    private void readAllele() throws IOException {
        GeneType type = GeneType.get(stream.readByte());
        if (type == null)
            throw new IOException("Unknown gene type.");

        //Reading
        String alleleName = stream.readUTF();
        byte flags = stream.readByte();
        String accession = null;
        int[] referencePoints = null;
        if ((flags & 4) != 0) {
            accession = stream.readUTF();
            referencePoints = new int[LociLibraryWriter.getGeneTypeInfo(type, false).size];
            for (int i = 0; i < referencePoints.length; ++i)
                referencePoints[i] = stream.readInt();
        }
        String referenceAllele = null;
        int[] mutations = null;
        GeneFeature referenceGeneFeature = null;
        if ((flags & 8) != 0) {
            referenceAllele = stream.readUTF();
            referenceGeneFeature = LociLibraryIOUtils.readReferenceGeneFeature(stream);
            int size = stream.readInt();
            mutations = new int[size];
            for (int i = 0; i < size; ++i)
                mutations[i] = stream.readInt();
        }

        //Adding
        String geneName = alleleName.substring(0, alleleName.lastIndexOf('*'));
        Gene gene = nameToGenes.get(geneName);
        if (gene == null) {
            if (referencePoints == null)
                throw new IOException("First gene allele is not reference.");
            List<Gene> gs = genes.get(type);
            gs.add(gene =
                    new Gene(gs.size(), geneName, GeneGroup.get(container.getSpeciesAndChain().chain, type), container));
            Gene gg = nameToGenes.put(geneName, gene);
            assert gg == null;
            allGenes.add(gene);
        }
        List<Allele> as = alleles.get(type);
        Allele parent = null;
        if (referenceAllele != null) {
            parent = nameToAlleles.get(referenceAllele);
            if (parent == null)
                throw new IOException("No parent allele.");
        }
        Allele allele;
        if ((flags & 1) != 0) {
            //reference allele
            allele = new ReferenceAllele(gene, alleleName, (flags & 2) != 0, accession,
                    LociLibraryWriter.getGeneTypeInfo(type, false).create(referencePoints));
        } else {
            //allelic variant
            allele = new AllelicVariant(alleleName,
                    (flags & 2) != 0, referenceGeneFeature,
                    (ReferenceAllele) parent,
                    new Mutations<>(NucleotideSequence.ALPHABET, mutations));
        }

        gene.alleles.add(allele);
        as.add(allele);
        parent = nameToAlleles.put(alleleName, allele);

        //No alleles with the same name
        assert parent == null;

        listener.allele(beginOfCurrentBlock, countingInputStream.getBytesRead(), allele);
    }

    private void endLocus() {
        for (Map.Entry<GeneType, List<Allele>> e : alleles.entrySet())
            e.setValue(Collections.unmodifiableList(
                    Arrays.asList(
                            e.getValue().toArray(new Allele[e.getValue().size()]))
            ));

        for (Map.Entry<GeneType, List<Gene>> e : genes.entrySet())
            e.setValue(Collections.unmodifiableList(
                    Arrays.asList(
                            e.getValue().toArray(new Gene[e.getValue().size()]))
            ));

        library.registerContainer(container);
        container = null;
        genes = null;
        alleles = null;
        nameToAlleles = null;
        nameToGenes = null;
        allGenes = null;
        listener.endLocus(beginOfCurrentBlock, countingInputStream.getBytesRead(), container);
    }

    private void readSequencePart(boolean compressed) throws IOException {
        String accession = stream.readUTF();
        int from = stream.readInt();
        Bit2Array seqContent;
        if (compressed) {
            int len = stream.readInt();
            byte[] buf = new byte[len];
            int size = stream.read(buf);
            assert size == buf.length;
            Inflater inflater = new Inflater(true);
            try (InflaterInputStream is = new InflaterInputStream(new ByteArrayInputStream(buf), inflater)) {
                seqContent = Bit2Array.readFrom(new DataInputStream(is));
            }
            assert inflater.finished();
            inflater.end();
        } else
            seqContent = Bit2Array.readFrom(stream);
        NucleotideSequence seq = SequencesUtils.convertBit2ArrayToNSequence(seqContent);
        library.base.put(accession, from, seq);
        listener.sequencePart(beginOfCurrentBlock, countingInputStream.getBytesRead(), from, seq);
    }

    private void readMeta() throws IOException {
        String key = stream.readUTF();
        String value = stream.readUTF();
        if (container == null)
            library.properties.put(key, value);
        else
            container.properties.put(key, value);
        listener.meta(beginOfCurrentBlock, countingInputStream.getBytesRead(), key, value);
    }

    private void readSpeciesName() throws IOException {
        if (container != null)
            throw new IOException("Illegal place for \"common species name\" record.");
        int taxonId = stream.readInt();
        String name = stream.readUTF();
        library.knownSpecies.put(name, taxonId);
        listener.speciesName(beginOfCurrentBlock, countingInputStream.getBytesRead(), taxonId, name);
    }
}
