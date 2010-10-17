package edu.rice.batchsig.splice;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.google.protobuf.ByteString;

import edu.rice.batchsig.Message;
import edu.rice.batchsig.SignaturePrimitives;
import edu.rice.batchsig.Verifier;
import edu.rice.batchsig.bench.IncomingMessage;
import edu.rice.batchsig.splice.Dag.DagNode;
import edu.rice.historytree.HistoryTree;

/** Represent all of the message from one history tree instance */
public class OneTree {
	int size = 0;
	public int size() {
		return size;
	}
	
	final private VerifyHisttreeLazily verifier;
	/** For each version number the message at that number. */
	HashMap<Integer,IncomingMessage> messages = new LinkedHashMap<Integer,IncomingMessage>(1,.75f,false);

	/** Cache of unvalidated root hashes of the history trees for messages in messages. msg.getTree().agg()
	 *    When we verify a splice, we need to take the child message's agg() and compare it to aggV(child.version).
	 *    Rather than rebuild the child's pruned tree, or cache the whole thing, just cache the parts we need, the agg().
	 *  */
	HashMap<Integer,byte[]> roothashes = new HashMap<Integer,byte[]>();

	/** Each node in the Dag corresponds the end-of-an-epoch bundle. This maps the version number that denotes a bundle to the set of messgaes in that bundle */
	Multimap<Integer,IncomingMessage> buckets = LinkedListMultimap.create();

	/** For various message indices, cache of the validated root hashes of history trees; if a new message arrives with a hash in this list, then the message is validated without a public key op. */
	HashMap<Integer,byte[]> validatedhashes = new HashMap<Integer,byte[]>();
	
	/**
	 * Invariant; The dag contains nodes for each message and the version
	 * numbers of the splicepoints for each message.
	 * 
	 * If an edge is in the dag and both messages have been seen (are in the
	 * messages hash), then the splice has been VERIFIED.
	 * 
	 * If an edge is in the dag, but either the from or to messages in the
	 * dag are *NOT* in the messages hash, then the splice is provisional
	 * and may not exist. We wait until the peer message arrives, verify the
	 * splice, and keep the edge if the splice verifies, or remove the edge
	 * if the splice fails.
	 * 
	 * */
	final private Dag<Integer> dag = new Dag<Integer>();
	final private Object author;
	final private long treeid;

	public Object getAuthor() {
		return author;
	}

	public long getTreeid() {
		return treeid;
	}

	Dag<Integer>.DagNode getNode(Message m) {
		Integer key = m.getSignatureBlob().getLeaf();
		return dag.makeOrGet(key);
	}

	// TODO: Remove this function!
	private String getName() {
		return String.format("OneTree(%s-%d)",((ByteString)author).toStringUtf8(),treeid);
	}
	
	
	public OneTree(VerifyHisttreeLazily verifier, Object object, long l) {
		this.author = object;
		this.treeid = l;
		this.verifier = verifier;
	}
	
	
	/* Each node in the dag corresponds to a set of messages. All that end in the same epoch. */
	void addMessage(IncomingMessage m) {
		System.out.println("Adding message "+m);
		size++;
		Integer key = m.getSignatureBlob().getLeaf();
		Integer bundlekey = m.getSignatureBlob().getTree().getVersion();
		HistoryTree<byte[],byte[]> tree = VerifyHisttreeLazily.parseHistoryTree(m);

		// First, see if this message is well-formed.
		if (!Verifier.checkLeaf(m,tree)) {
			System.out.println("Broken proof that doesn't validate message in proof.");
		}
		
		// First: Check to see if this message is in the validated hashes.
		if (validatedhashes.get(key) != null && Arrays.equals(tree.agg(),validatedhashes.get(key))) {
			// Already done!
			System.out.println("Hash has been seen and validated already!");
			m.signatureValidity(true);
			return;
		}
		
		// Nope. Now enter the core processing loop.
		
		if (messages.containsKey(key)) {
			// PROBLEM: Have a message with this version number already.
			// TODO: Can't handle these at all. Only solution: Verify it immediately.
			throw new Error("TODO");
		}

		// By definition, at this point, we have never seen this message before.
		Dag<Integer>.DagNode node = getNode(m);
		
		// PART 1: See if we've seen later messages we might splice into.
		// This case should be rare and only occur when messages arrive out-of-order.
		for (Dag<Integer>.DagNode succ : node.getParents()) {
			// For each later message in the dag that provisionally splices this message.
			Integer succi = succ.get();
			Message succm = messages.get(succi);
			if (succm == null)
				throw new Error("Algorithm bug.");
			// Time to verify the splice is OK. 
			HistoryTree<byte[],byte[]> succtree = VerifyHisttreeLazily.parseHistoryTree(succm);
			if (Arrays.equals(succtree.aggV(key.intValue()),tree.agg())) {
				System.out.println("Unusual splice circumstance -- success");
				; // Splice verifies. Nothing to be done.
			} else {
				// Splice fails. Remove the edge.
				System.out.println("Unusual splice circumstance -- failure & removal");
				dag.removeEdge(succ,node);
			}
		}
			
		// PART 2: See which prior messages we splice into.
		for (Integer predi : m.getSignatureBlob().getSpliceHintList()) {
			System.out.println("Handling splicehint: "+predi);
			// For each splicepoint to prior messages in this message,
			Message predm = messages.get(predi);
			Dag<Integer>.DagNode prednode;
			// Have we seen the prior message?
			if (predm == null) {
				System.out.println("No priors found");
				// Nope. Add the node to the dag. Add an edge to that child; it'll be provisional
				prednode = dag.makeOrGet(predi);
				dag.addEdge(node,prednode);
			} else {
				// We have seen that message. We need to check the splice.
				if (Arrays.equals(tree.aggV(predi.intValue()),roothashes.get(predm))) {
					System.out.println("Found a prior. Verified the splice!");
					; // Splice verifies. Nothing to be done.
				} else {
					// Splice fails. Remove the edge.
					System.out.println("Found a prior, but splice failed");
					prednode = dag.makeOrGet(predi);
					dag.removeEdge(node,prednode);
				}
			}
		}
		//roothashes.put(m,tree.agg());
		messages.put(key, m);
		System.out.println("Finished handling for message");
	}

	private void remove(IncomingMessage m) {
		size--;
		verifier.removeMessage(m);
		messages.remove(m.getSignatureBlob().getLeaf());
		roothashes.remove(m);
		getNode(m).remove();
	}

	
	void forceOldest() {
		System.out.format("Forcing oldest message to OneTree\n");

		Iterator<Integer> i = messages.keySet().iterator();
		if (!i.hasNext()) {
			throw new Error("Can ignore this error, but we shouldn't be forcing the oldest on an empty onetree");
		}
		forceMessage(messages.get(i.next()));
	}
	
	
	
	void forceMessage(IncomingMessage m) {
		System.out.format(">>>>> Forcing a message %d to %s\n",m.getSignatureBlob().getLeaf(),getName());
		Dag<Integer>.DagNode node = getNode(m);

		// Step one: Find a root.
		Dag<Integer>.Path rootPath = dag.rootPath(node);
		// Step two, until we find a root whose signature verifies.
		while (true) {
			System.out.println("WhileLoop at rootPath ="+rootPath);
			Dag<Integer>.DagNode root = rootPath.root();
			Integer rooti = root.get();
			IncomingMessage rootm = messages.get(rooti);
			HistoryTree<byte[],byte[]> roottree = verifier.parseHistoryTree(rootm);

			System.out.println("Got root... about to see if it verified");
			// Verify the root's public key signature.
			if (verifier.verifyHistoryRoot(rootm,roottree)) {
				System.out.println("Verified the root's signature - SUCCESS. It is valid");
				// Success!
				// Now traverse *all* descendents and mark them as good.
				Collection<Dag<Integer>.DagNode> descendents = dag.getAllChildren(root);
				for (Dag<Integer>.DagNode i : descendents) {
					System.out.println("Traversing descendent to mark as valid:"+i.get());
					//Integer desci = i.get();
					IncomingMessage descm = messages.get(i);
					if (descm != null) {
						System.out.println("... and marking it as good!");
						// This message is not provisional. It is valid.
						descm.signatureValidity(true);
						// TODO: Cache the spliced predecessor hashes from this node as being valid?
						// Remove it from further tracking.
						remove(descm);
					}
				}
				// Mark the root as valid.
				rootm.signatureValidity(true);
				remove(rootm);

				System.out.format("<<<<<< Done with handling force of %d to %s\n",m.getSignatureBlob().getLeaf(),getName());
				return;
			} else {
				System.out.println("Failed the root's signature");
				rootm.signatureValidity(false);
				remove(rootm);
			}
			System.out.println("Got a problem, need to try the next root");

			// Failure. Try the next root.
			rootPath.next();
		}
		
		
	}
	
	public void forceAll() {
		System.out.format("Forcing all messages in OneTree\n");
		while (!messages.isEmpty())
			forceMessage(messages.entrySet().iterator().next().getValue());
	}
	
	public boolean isEmpty() {
		return messages.isEmpty();
	}
}