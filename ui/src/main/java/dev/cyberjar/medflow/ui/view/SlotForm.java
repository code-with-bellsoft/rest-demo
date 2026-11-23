package dev.cyberjar.medflow.ui.view;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import dev.cyberjar.medflow.ui.dto.CreateSlotRequest;
import dev.cyberjar.medflow.ui.dto.ReferenceDto;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

class SlotForm extends Dialog {

    SlotForm(List<ReferenceDto> facilities, List<ReferenceDto> specialties, Predicate<CreateSlotRequest> submit) {
        setHeaderTitle("Create appointment slot");
        var facility = referenceBox("Facility");
        facility.setItems(facilities);
        var specialty = referenceBox("Specialty");
        specialty.setItems(specialties);
        var start = dateTime("Start time (UTC)");
        var end = dateTime("End time (UTC)");
        var capacity = new IntegerField("Capacity");

        var fields = new FormLayout(facility, specialty, start, end, capacity);
        fields.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("27rem", 2));
        setWidth("36rem");
        setMaxWidth("95vw");
        add(fields);
        var save = new Button("Create slot", event -> {
            var request = new CreateSlotRequest(id(facility), id(specialty),
                    utc(start.getValue()), utc(end.getValue()), capacity.getValue());
            if (submit.test(request)) {
                close();
            }
        });
        save.addThemeVariants(ButtonVariant.PRIMARY);
        getFooter().add(new Button("Cancel", event -> close()), save);
    }

    static ComboBox<ReferenceDto> referenceBox(String label) {
        var box = new ComboBox<ReferenceDto>(label);
        box.setItemLabelGenerator(ReferenceDto::name);
        box.setClearButtonVisible(true);
        return box;
    }

    static Long id(ComboBox<ReferenceDto> box) {
        return box.isEmpty() ? null : box.getValue().id();
    }

    private static DateTimePicker dateTime(String label) {
        var picker = new DateTimePicker(label);
        picker.setLocale(Locale.UK);
        picker.setAutoOpen(false);
        picker.setDatePlaceholder("DD/MM/YYYY");
        picker.setTimePlaceholder("HH:MM");
        return picker;
    }

    private static OffsetDateTime utc(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
