package dev.roots;

public interface Page extends Component {
    default Metadata metadata(PageContext context) {
        return Metadata.DEFAULT;
    }

}
