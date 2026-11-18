import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

@Component
public class DbEnvPrinter {
    @Autowired
    private Environment env;

    @PostConstruct
    public void printEnv() {
        System.out.println("====== DB URL: " + env.getProperty("spring.datasource.url") + " ======");
    }
}
