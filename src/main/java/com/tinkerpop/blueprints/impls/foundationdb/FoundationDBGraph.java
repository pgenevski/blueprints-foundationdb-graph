package com.tinkerpop.blueprints.impls.foundationdb;

import java.util.*;

import com.foundationdb.KeyValue;
import com.tinkerpop.blueprints.*;
import com.foundationdb.Database;
import com.foundationdb.FDB;
import com.foundationdb.Transaction;
import com.foundationdb.tuple.Tuple;
import com.tinkerpop.blueprints.impls.foundationdb.util.FoundationDBGraphUtils;
import com.tinkerpop.blueprints.util.PropertyFilteredIterable;

public class FoundationDBGraph implements KeyIndexableGraph, IndexableGraph {
	
	public Database db;
	private FDB fdb;
	protected String graphName;
	public static final Features FEATURES = new Features();
    private Set<String> vertexKeyIndices;
    private Set<String> edgeKeyIndices;
	
	static {
        FEATURES.supportsDuplicateEdges = true;
        FEATURES.supportsSelfLoops = true;
        FEATURES.isPersistent = true;
        FEATURES.supportsVertexIteration = true;
        FEATURES.supportsEdgeIteration = true;
        FEATURES.supportsVertexIndex = true;
        FEATURES.supportsEdgeIndex = true;
        FEATURES.ignoresSuppliedIds = false;
        FEATURES.supportsEdgeRetrieval = true;
        FEATURES.supportsVertexProperties = true;
        FEATURES.supportsEdgeProperties = true;
        FEATURES.supportsTransactions = false;
        FEATURES.supportsIndices = true;

        FEATURES.supportsSerializableObjectProperty = false;
        FEATURES.supportsBooleanProperty = true;
        FEATURES.supportsDoubleProperty = true;
        FEATURES.supportsFloatProperty = true;
        FEATURES.supportsIntegerProperty = true;
        FEATURES.supportsPrimitiveArrayProperty = false;
        FEATURES.supportsUniformListProperty = false;
        FEATURES.supportsMixedListProperty = false;
        FEATURES.supportsLongProperty = true;
        FEATURES.supportsMapProperty = false;
        FEATURES.supportsStringProperty = true;

        FEATURES.isWrapper = false;
        FEATURES.isRDFModel = false;
        FEATURES.supportsKeyIndices = true;
        FEATURES.supportsVertexKeyIndex = true;
        FEATURES.supportsEdgeKeyIndex = true;
        FEATURES.supportsThreadedTransactions = false;
    }
	
	public FoundationDBGraph() {
		this(UUID.randomUUID().toString());
	}
	
	public FoundationDBGraph(String graphName) {
		this.graphName = graphName;
		this.fdb = FDB.selectAPIVersion(21);
		this.db = fdb.open().get();
        prepareKeyIndexCache();
	}

    private void prepareKeyIndexCache() {
        this.vertexKeyIndices = new TreeSet<String>();
        Transaction tr = db.createTransaction();
        List<KeyValue> kvs = tr.getRangeStartsWith(graphPrefix().add("ki").add("v").pack()).asList().get();
        for (KeyValue kv : kvs) {
            this.vertexKeyIndices.add(Tuple.fromBytes(kv.getKey()).getString(4));
        }
        this.edgeKeyIndices = new TreeSet<String>();
        kvs = tr.getRangeStartsWith(graphPrefix().add("ki").add("e").pack()).asList().get();
        for (KeyValue kv : kvs) {
            this.edgeKeyIndices.add(Tuple.fromBytes(kv.getKey()).getString(4));
        }
        tr.commit().get();
    }
	
	public Features getFeatures() {
		return FEATURES;
	}

	@Override
	public Edge addEdge(Object id, Vertex outVertex, Vertex inVertex, String label) {
		FoundationDBEdge e;
        if (id != null) e = new FoundationDBEdge(this, id.toString());
        else e = new FoundationDBEdge(this);
        Transaction tr = db.createTransaction();
        tr.set(graphPrefix().add("e").add(e.getId()).pack(), label.getBytes());
        tr.set(graphPrefix().add("in").add("e").add(e.getId()).pack(), inVertex.getId().toString().getBytes());
        tr.set(graphPrefix().add("out").add("e").add(e.getId()).pack(), outVertex.getId().toString().getBytes());
        tr.set(graphPrefix().add("in").add("v").add(inVertex.getId().toString()).add(e.getId()).pack(), "".getBytes());
        tr.set(graphPrefix().add("out").add("v").add(outVertex.getId().toString()).add(e.getId()).pack(), "".getBytes());
        tr.commit().get();
        return e;
	}

	@Override
	public FoundationDBVertex addVertex(Object id) {
        FoundationDBVertex v;
		if (id != null) v = new FoundationDBVertex(this, id.toString());
        else v = new FoundationDBVertex(this);
		Transaction tr = db.createTransaction();
		tr.set(graphPrefix().add("v").add(v.getId()).pack(), v.getId().getBytes());
        tr.commit().get();
		return v;
	}

	@Override
	public Edge getEdge(Object id) {
        if (id == null) throw new IllegalArgumentException();
        FoundationDBEdge e = new FoundationDBEdge(this, id.toString());
        if (this.hasEdge(e)) return e;
        else return null;
	}

	@Override
	public Iterable<Edge> getEdges() {
        List<Edge> edges = new ArrayList<Edge>();
        Transaction tr = db.createTransaction();
        List<KeyValue> keyValueList = tr.getRangeStartsWith(graphPrefix().add("e").pack()).asList().get();
        for (KeyValue kv: keyValueList) {
            edges.add(new FoundationDBEdge(this, Tuple.fromBytes(kv.getKey()).getString(3)));
        }
        return edges;
	}

	public Iterable<Edge> getEdges(String key, Object value) {
        if (this.hasKeyIndex(key, Edge.class)) {
            Transaction tr = db.createTransaction();
            List<KeyValue> kvs = tr.getRangeStartsWith(graphPrefix().add("kid").add("e").add(key).addObject(value).pack()).asList().get();
            ArrayList<Edge> edges = new ArrayList<Edge>();
            for (KeyValue kv : kvs) {
                edges.add(new FoundationDBEdge(this, Tuple.fromBytes(kv.getKey()).getString(6)));
            }
            return edges;
        }
        else {
            return new PropertyFilteredIterable<Edge>(key, value, this.getEdges());
        }
	}

	@Override
	public FoundationDBVertex getVertex(Object id) {
		if (id == null) throw new IllegalArgumentException();
		FoundationDBVertex v = new FoundationDBVertex(this, id.toString());
		if (this.hasVertex(v)) return v;
		else return null;
	}

    private Boolean hasVertex(Vertex v) {
        Transaction tr = db.createTransaction();
        return tr.get(graphPrefix().add("v").add(v.getId().toString()).pack()).get() != null;
    }

    private Boolean hasEdge(Edge e) {
        Transaction tr = db.createTransaction();
        return tr.get(graphPrefix().add("e").add(e.getId().toString()).pack()).get() != null;
    }

	@Override
	public Iterable<Vertex> getVertices() {
        List<Vertex> vertices = new ArrayList<Vertex>();
        Transaction tr = db.createTransaction();
        List<KeyValue> keyValueList = tr.getRangeStartsWith(graphPrefix().add("v").pack()).asList().get();
        for (KeyValue kv: keyValueList) {
            vertices.add(new FoundationDBVertex(this, new String(kv.getValue())));
        }
        return vertices;
	}

	@Override
	public Iterable<Vertex> getVertices(String key, Object value) {
        if (this.hasKeyIndex(key, Vertex.class)) {
            Transaction tr = db.createTransaction();
            List<KeyValue> kvs = tr.getRangeStartsWith(graphPrefix().add("kid").add("v").add(key).addObject(value).pack()).asList().get();
            ArrayList<Vertex> vertices = new ArrayList<Vertex>();
            for (KeyValue kv : kvs) {
                vertices.add(new FoundationDBVertex(this, Tuple.fromBytes(kv.getKey()).getString(6)));
            }
            return vertices;
        }
        else {
            return new PropertyFilteredIterable<Vertex>(key, value, this.getVertices());
        }
	}

	@Override
	public GraphQuery query() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void removeEdge(Edge e) {
        if (!hasEdge(e)) throw new IllegalArgumentException("Edge does not exist!");
        Transaction tr = db.createTransaction();
        Vertex inVertex = e.getVertex(Direction.IN);
        Vertex outVertex = e.getVertex(Direction.OUT);
        tr.clear(graphPrefix().add("in").add("v").add(inVertex.getId().toString()).add(e.getId().toString()).pack());
        tr.clear(graphPrefix().add("out").add("v").add(outVertex.getId().toString()).add(e.getId().toString()).pack());
        tr.clearRangeStartsWith(graphPrefix().add("e").add(e.getId().toString()).pack());
        tr.clearRangeStartsWith(graphPrefix().add("in").add("e").add(e.getId().toString()).pack());
        tr.clearRangeStartsWith(graphPrefix().add("out").add("e").add(e.getId().toString()).pack());
        tr.clearRangeStartsWith(graphPrefix().add("p").add("e").add(e.getId().toString()).pack());
        byte[] reverseIndexKey = graphPrefix().add("ri").add("e").add(e.getId().toString()).pack();
        List<KeyValue> reverseIndexValues = tr.getRangeStartsWith(reverseIndexKey).asList().get();
        for (KeyValue kv : reverseIndexValues) {
            FoundationDBIndex<Edge> index = new FoundationDBIndex<Edge>(Tuple.fromBytes(kv.getKey()).getString(5), Edge.class, this);
            index.remove(Tuple.fromBytes(kv.getKey()).getString(6), Tuple.fromBytes(kv.getKey()).get(7), e);
        }
        tr.clearRangeStartsWith(reverseIndexKey);
        tr.commit().get();
	}

	@Override
	public void removeVertex(Vertex v) {
        if (!hasVertex(v)) throw new IllegalArgumentException("Vertex does not exist!");
		for (Edge e : v.getEdges(Direction.BOTH)) {
            if (hasEdge(e)) this.removeEdge(e);
        }
        Transaction tr = db.createTransaction();
        tr.clearRangeStartsWith(graphPrefix().add("v").add(v.getId().toString()).pack());
        tr.clearRangeStartsWith(graphPrefix().add("in").add("v").add(v.getId().toString()).pack());
        tr.clearRangeStartsWith(graphPrefix().add("out").add("v").add(v.getId().toString()).pack());
        tr.clearRangeStartsWith(graphPrefix().add("p").add("v").add(v.getId().toString()).pack());
        byte[] reverseIndexKey = graphPrefix().add("ri").add("v").add(v.getId().toString()).pack();
        List<KeyValue> reverseIndexValues = tr.getRangeStartsWith(reverseIndexKey).asList().get();
        for (KeyValue kv : reverseIndexValues) {
            FoundationDBIndex<Vertex> index = new FoundationDBIndex<Vertex>(Tuple.fromBytes(kv.getKey()).getString(5), Vertex.class, this);
            index.remove(Tuple.fromBytes(kv.getKey()).getString(6), Tuple.fromBytes(kv.getKey()).get(7), v);
        }
        tr.clearRangeStartsWith(reverseIndexKey);
        tr.commit().get();
	}

	@Override
	public String toString() {
		return "foundationdbgraph [graphName=" + graphName + "]";
	}

	public void shutdown() {

	}

    public Tuple graphPrefix() {
        return new Tuple().add(0).add(this.graphName);
    }

    public void purge() {
        Transaction tr = db.createTransaction();
        tr.clearRangeStartsWith(graphPrefix().pack());
        tr.commit().get();
    }

    public <T extends Element> Index<T> createIndex(String name, Class<T> type, Parameter... args) {
        if (this.hasIndex(name, type)) throw new IllegalStateException();
        Transaction tr = db.createTransaction();
        tr.set(graphPrefix().add("i").add(name).pack(), type.getSimpleName().getBytes());
        tr.commit().get();
        return new FoundationDBIndex<T>(name, type, this);
    }

    public <T extends Element> Index<T> getIndex(String name, Class<T> type) {
        FoundationDBIndex<T> index = new FoundationDBIndex<T>(name, type, this);
        if (this.hasIndex(name, type)) return index;
        else return null;
    }

    public void dropIndex(String name) {
        Transaction tr = db.createTransaction();
        tr.clearRangeStartsWith(graphPrefix().add("i").add(name).pack());
        tr.commit().get();
    }

    public Iterable<Index<? extends Element>> getIndices() {
        List<Index<? extends Element>> indices = new ArrayList<Index<? extends Element>>();
        Transaction tr = db.createTransaction();
        List<KeyValue> kvs= tr.getRangeStartsWith(graphPrefix().add("i").pack()).asList().get();
        for (KeyValue kv : kvs) {
            if (new String(kv.getValue()).equals("Vertex")) {
                indices.add(new FoundationDBIndex<Vertex>(Tuple.fromBytes(kv.getKey()).getString(3), Vertex.class, this));
            }
            else if (new String(kv.getValue()).equals("Edge")) {
                indices.add(new FoundationDBIndex<Edge>(Tuple.fromBytes(kv.getKey()).getString(3), Edge.class, this));
            }
        }
        return indices;
    }

    private <T extends Element> boolean hasIndex(String name, Class<T> type) {
        Transaction tr = db.createTransaction();
        byte[] bytes = tr.get(graphPrefix().add("i").add(name).pack()).get();
        return (bytes != null && new String(bytes).equals(type.getSimpleName()));
    }

    public <T extends Element> void dropKeyIndex(String key, Class<T> elementClass) {
        Transaction tr = db.createTransaction();
        tr.clear(graphPrefix().add("ki").add(getElementString(elementClass)).add(key).pack());
        tr.clearRangeStartsWith(graphPrefix().add("kid").add(getElementString(elementClass)).add(key).pack());
        tr.commit().get();
        removeIndexFromCache(key, elementClass);
    }


    public <T extends Element> void createKeyIndex(String key, Class<T> elementClass, final Parameter... indexParameters) {
        if (this.hasKeyIndex(key, elementClass)) throw new IllegalArgumentException();
        Transaction tr = db.createTransaction();
        tr.set(graphPrefix().add("ki").add(getElementString(elementClass)).add(key).pack(), "".getBytes());
        tr.commit().get();
        reindexElements(key, elementClass);
        addIndexToCache(key, elementClass);
    }

    private void reindexElements(String key, Class<? extends Element> elementClass) {
        if (elementClass.equals(Vertex.class)) {
            Transaction tr = db.createTransaction();
            Iterable<Vertex> vertices = this.getVertices();
            for (Vertex v : vertices) {
                if (v.getPropertyKeys().contains(key)) {
                    tr.set(graphPrefix().add("kid").add(getElementString(elementClass)).add(key).addObject(FoundationDBGraphUtils.getStoreableValue(v.getProperty(key))).add(v.getId().toString()).pack(), "".getBytes());
                }
            }
            tr.commit().get();
        }
        else if (elementClass.equals(Edge.class)) {
            Transaction tr = db.createTransaction();
            Iterable<Edge> edges = this.getEdges();
            for (Edge e : edges) {
                if (e.getPropertyKeys().contains(key)) {
                    tr.set(graphPrefix().add("kid").add(getElementString(elementClass)).add(key).addObject(FoundationDBGraphUtils.getStoreableValue(e.getProperty(key))).add(e.getId().toString()).pack(), "".getBytes());
                }
            }
            tr.commit().get();
        }
        else throw new IllegalArgumentException();
    }


    public final <T extends Element> Set<String> getIndexedKeys(Class<T> elementClass) {
        if (elementClass.equals(Vertex.class)) return this.vertexKeyIndices;
        else if (elementClass.equals(Edge.class)) return this.edgeKeyIndices;
        else throw new IllegalStateException();
    }

    public String getElementString(Class elementClass) {     //todo
        if (elementClass.equals(Vertex.class)) return "v";
        else if (elementClass.equals(Edge.class)) return "e";
        else throw new IllegalStateException();
    }

    private <T extends Element> void addIndexToCache(String key, Class<T> elementClass) {
        if (elementClass.equals(Vertex.class)) this.vertexKeyIndices.add(key);
        else if (elementClass.equals(Edge.class)) this.edgeKeyIndices.add(key);
        else throw new IllegalStateException();
    }

    private <T extends Element> void removeIndexFromCache(String key, Class<T> elementClass) {
        if (elementClass.equals(Vertex.class)) this.vertexKeyIndices.remove(key);
        else if (elementClass.equals(Edge.class)) this.edgeKeyIndices.remove(key);
        else throw new IllegalStateException();
    }

    public <T extends Element> boolean hasKeyIndex(String key, Class<T> elementClass) {
        if (elementClass.equals(Vertex.class)) return this.vertexKeyIndices.contains(key);
        else if (elementClass.equals(Edge.class)) return this.edgeKeyIndices.contains(key);
        else throw new IllegalStateException();
    }
}
