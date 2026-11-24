package dev.nezz.rentguard.ledger;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Binary Merkle tree over audit entry hashes. An odd node at any level is paired with itself. A proof lets a client
 * check that one entry is in a block using about log2(n) hashes instead of the whole block.
 */
public final class MerkleTree {

    public record ProofStep(String siblingHash, boolean siblingOnLeft) {
    }

    private MerkleTree() {
    }

    public static String root(List<String> leafHashes) {
        if (leafHashes.isEmpty()) {
            return Hashes.sha256Hex("");
        }
        List<String> level = leafHashes;
        while (level.size() > 1) {
            level = nextLevel(level);
        }
        return level.getFirst();
    }

    public static List<ProofStep> proof(List<String> leafHashes, int index) {
        if (index < 0 || index >= leafHashes.size()) {
            throw new IndexOutOfBoundsException("no leaf " + index);
        }
        List<ProofStep> steps = new ArrayList<>();
        List<String> level = leafHashes;
        int position = index;
        while (level.size() > 1) {
            boolean isRight = position % 2 == 1;
            int siblingIndex = isRight ? position - 1 : Math.min(position + 1, level.size() - 1);
            steps.add(new ProofStep(level.get(siblingIndex), isRight));
            level = nextLevel(level);
            position /= 2;
        }
        return steps;
    }

    public static boolean verify(String leafHash, List<ProofStep> proof, String expectedRoot) {
        String current = leafHash;
        for (ProofStep step : proof) {
            current = step.siblingOnLeft() ? pair(step.siblingHash(), current) : pair(current, step.siblingHash());
        }
        return current.equals(expectedRoot);
    }

    private static List<String> nextLevel(List<String> level) {
        List<String> next = new ArrayList<>((level.size() + 1) / 2);
        for (int i = 0; i < level.size(); i += 2) {
            String left = level.get(i);
            String right = i + 1 < level.size() ? level.get(i + 1) : left;
            next.add(pair(left, right));
        }
        return next;
    }

    private static String pair(String left, String right) {
        MessageDigest digest = Hashes.newSha256();
        digest.update(Hashes.unhex(left));
        digest.update(Hashes.unhex(right));
        return Hashes.hex(digest.digest());
    }
}
