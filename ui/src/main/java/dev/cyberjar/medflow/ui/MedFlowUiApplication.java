package dev.cyberjar.medflow.ui;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.theme.aura.Aura;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@StyleSheet(Aura.STYLESHEET)
@StyleSheet("medflow.css")
public class MedFlowUiApplication implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(MedFlowUiApplication.class, args);
    }
}
