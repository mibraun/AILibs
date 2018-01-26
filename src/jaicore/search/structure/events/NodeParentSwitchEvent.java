package jaicore.search.structure.events;

public class NodeParentSwitchEvent<T> {
	private final T node;
	private final T oldParent;
	private final T newParent;
	
	public NodeParentSwitchEvent(T node,T oldParent, T newParent){
		super();
		this.node = node;
		this.oldParent = oldParent;
		this.newParent = newParent;
	}

	public T getNode() {
		return node;
	}

	public T getOldParent() {
		return oldParent;
	}
	public T getNewParent() {
		return newParent;
	}
	
	

}