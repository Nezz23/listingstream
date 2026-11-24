package dev.nezz.rentguard.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MerkleTreeTest {

    private static List<String> leaves(int n) {
        return IntStream.range(0, n).mapToObj(i -> Hashes.sha256Hex("tx-" + i)).toList();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 5, 8, 13, 100})
    void everyLeafHasAProofThatVerifies(int n) {
        List<String> leaves = leaves(n);
        String root = MerkleTree.root(leaves);
        for (int i = 0; i < n; i++) {
            assertThat(MerkleTree.verify(leaves.get(i), MerkleTree.proof(leaves, i), root)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 7, 64})
    void aSubstitutedLeafFailsItsProof(int n) {
        List<String> leaves = leaves(n);
        String root = MerkleTree.root(leaves);
        List<MerkleTree.ProofStep> proof = MerkleTree.proof(leaves, n - 1);
        assertThat(MerkleTree.verify(Hashes.sha256Hex("forged"), proof, root)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {16, 1000})
    void proofLengthGrowsLogarithmically(int n) {
        int expected = (int) Math.ceil(Math.log(n) / Math.log(2));
        assertThat(MerkleTree.proof(leaves(n), 0)).hasSize(expected);
    }
}
