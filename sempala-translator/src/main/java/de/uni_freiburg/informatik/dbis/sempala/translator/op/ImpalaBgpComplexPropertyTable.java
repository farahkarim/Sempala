package de.uni_freiburg.informatik.dbis.sempala.translator.op;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.shared.PrefixMapping;
import com.hp.hpl.jena.sparql.algebra.op.OpBGP;

import de.uni_freiburg.informatik.dbis.sempala.translator.sql.ImpalaComplexTripleGroup;
import de.uni_freiburg.informatik.dbis.sempala.translator.sql.Join;
import de.uni_freiburg.informatik.dbis.sempala.translator.sql.JoinType;
import de.uni_freiburg.informatik.dbis.sempala.translator.sql.JoinUtil;
import de.uni_freiburg.informatik.dbis.sempala.translator.sql.SQLStatement;
import de.uni_freiburg.informatik.dbis.sempala.translator.sql.Schema;


public class ImpalaBgpComplexPropertyTable extends ImpalaBGP {

	public ImpalaBgpComplexPropertyTable(OpBGP opBGP, PrefixMapping prefixes) {
		super(opBGP, prefixes);
	}

	@Override
	public SQLStatement translate(String resultName) {
		this.resultName = resultName;

		List<Triple> triples = opBGP.getPattern().getList();

		HashMap<Node, ImpalaComplexTripleGroup> tripleGroups = new HashMap<Node, ImpalaComplexTripleGroup>();

		// empty PrefixMapping when prefixes should be expanded
		if (expandPrefixes) {
			prefixes = PrefixMapping.Factory.create();
		}

		// Partition triples by common subject into triple groups.
		// Each triple group can then result into its own subquery.
		// Finally all subqueries are joined by shared variable.

		for (Triple triple : triples) {
			Node key = null;
			boolean fromTripletable = false;
			if (triple.getPredicate().isVariable()) {
				key = triple.getPredicate();
				fromTripletable = true;
			} else {
				key = triple.getSubject();
				fromTripletable = false;
			}

			if (!tripleGroups.containsKey(key)) {
				tripleGroups.put(key, new ImpalaComplexTripleGroup(this.resultName + "_"
						+ tableNumber++, prefixes, fromTripletable));
			}
			tripleGroups.get(key).add(triple);
		}

		ImpalaComplexTripleGroup group = null;
		ArrayList<ImpalaComplexTripleGroup> groups = new ArrayList<ImpalaComplexTripleGroup>();
		groups.addAll(tripleGroups.values());
		group = groups.get(0);
		groups.remove(0);


		// joins are necessary
		if (groups.size() > 0) {
			ArrayList<String> onConditions = new ArrayList<String>();
			ArrayList<SQLStatement> rights = new ArrayList<SQLStatement>();
			// Greedy approach: Find join partner with most shared vars.
			Map<String, String[]> group_shifted = Schema.shiftToParent(group.getMappings(), group.getName());
			while (groups.size() > 0) {
				int index = findBestJoin(group_shifted, groups);
				ImpalaComplexTripleGroup right = groups.get(index);
				Map<String, String[]> right_shifted = Schema.shiftToParent(right.getMappings(), right.getName());
				onConditions.add(JoinUtil.generateConjunction(JoinUtil
						.getOnConditions(group_shifted, right_shifted)));
				group_shifted.putAll(right_shifted);
				rights.add(right.translate());
				groups.remove(index);

			}
			this.resultSchema = group_shifted;
			Join join = new Join(getResultName(), group.translate(), rights, onConditions, JoinType.INNER);
			join.setDistinct(true);
			return join;
		}
		// no join needed
		this.resultName = group.getName();
		SQLStatement res = group.translate();
		this.resultSchema = group.getMappings();
		return res;
	}

	/**
	 * Finds index of best join partner.
	 * @param group_shifted
	 * @param groups
	 * @return index in list
	 */
	public int findBestJoin(Map<String, String[]> group_shifted, ArrayList<ImpalaComplexTripleGroup> groups) {
		int best = -1;
		int index = 0;
		for (int i = 0; i < groups.size(); i++) {
			int sharedVars = JoinUtil.getSharedVars(group_shifted, groups.get(i).getMappings()).size();
			if(sharedVars > best){
				best = sharedVars;
				index = i;
			}
		}
		return index;
	}
	
}