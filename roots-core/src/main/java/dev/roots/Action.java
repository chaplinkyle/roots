package dev.roots;

@FunctionalInterface
public interface Action {
    void handle(ActionEvent event) throws Exception;
}
