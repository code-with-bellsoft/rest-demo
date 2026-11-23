package dev.cyberjar.medflow.ui.view;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import dev.cyberjar.medflow.ui.client.SlotApiClient;
import dev.cyberjar.medflow.ui.client.SlotApiClient.Result;
import dev.cyberjar.medflow.ui.dto.ReferenceDto;
import dev.cyberjar.medflow.ui.dto.SlotDto;
import dev.cyberjar.medflow.ui.dto.UpdateCapacityRequest;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.json.JsonMapper;

@Route("")
@PageTitle("MedFlow | Appointment Slot Manager")
public class SlotView extends Div implements AfterNavigationObserver {

    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm xxx", Locale.ENGLISH);

    private final SlotApiClient api;
    private final HttpExchangePanel inspector;
    private final Grid<SlotDto> grid = new Grid<>();
    private final ComboBox<ReferenceDto> facility = SlotForm.referenceBox("Facility");
    private final ComboBox<ReferenceDto> specialty = SlotForm.referenceBox("Specialty");
    private final Paragraph status = new Paragraph();
    private List<ReferenceDto> facilities;
    private List<ReferenceDto> specialties;
    private boolean clearing;

    public SlotView(SlotApiClient api, JsonMapper mapper) {
        this.api = api;
        inspector = new HttpExchangePanel(mapper);
        addClassName("slot-view");
        var header = new Div(new H1("MedFlow | Appointment Slot Manager"),
                new Paragraph("Spring Boot REST API demonstration"));
        header.addClassName("app-header");

        var create = new Button("New slot", event -> openCreate());
        create.addThemeVariants(ButtonVariant.PRIMARY);
        var edit = new Button("Edit capacity", event -> openEdit(grid.asSingleSelect().getValue()));
        var delete = new Button("Delete", event -> confirmDelete(grid.asSingleSelect().getValue()));
        delete.addThemeVariants(ButtonVariant.AURA_DANGER);
        edit.setEnabled(false);
        delete.setEnabled(false);
        grid.asSingleSelect().addValueChangeListener(event -> {
            edit.setEnabled(event.getValue() != null);
            delete.setEnabled(event.getValue() != null);
        });

        var actions = new Div(create, edit, delete);
        actions.addClassName("slot-actions");
        var tableHeading = new Div(new H2("Appointment slots"), actions);
        tableHeading.addClassName("table-heading");
        var filters = new Div(facility, specialty, new Button("Clear filters", event -> clearFilters()),
                new Button("Refresh", event -> refresh()));
        filters.addClassName("slot-filters");
        facility.addValueChangeListener(event -> filterChanged());
        specialty.addValueChangeListener(event -> filterChanged());
        status.addClassName("slot-status");
        status.getElement().setAttribute("role", "status");

        grid.setAriaLabel("Appointment slots");
        grid.setEmptyStateText("No appointment slots to display.");
        grid.addColumn(SlotDto::id).setHeader("Slot ID").setWidth("5rem").setFlexGrow(0);
        grid.addColumn(SlotDto::facilityName).setHeader("Facility").setAutoWidth(true);
        grid.addColumn(SlotDto::specialtyName).setHeader("Specialty").setAutoWidth(true);
        grid.addColumn(slot -> DISPLAY_TIME.format(slot.startsAt())).setHeader("Start time").setAutoWidth(true);
        grid.addColumn(slot -> DISPLAY_TIME.format(slot.endsAt())).setHeader("End time").setAutoWidth(true);
        grid.addColumn(SlotDto::capacity).setHeader("Capacity").setWidth("6rem").setFlexGrow(0);
        grid.addColumn(SlotDto::activeBookings).setHeader("Booked").setWidth("5.5rem").setFlexGrow(0);
        grid.addColumn(SlotDto::remainingCapacity).setHeader("Remaining").setWidth("7rem").setFlexGrow(0);
        grid.setHeight("18rem");

        var table = new Div(tableHeading, filters, grid, status);
        table.addClassName("slot-table");
        add(header, table, inspector);
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        refresh();
    }

    private void refresh() {
        var referenceFailure = loadReferences();
        var slots = loadSlots(true);
        if (slots.succeeded() && referenceFailure != null) {
            reportFailure(referenceFailure);
        }
    }

    private Result<?> loadReferences() {
        Result<?> failure = null;
        if (facilities == null) {
            var result = api.facilities();
            if (result.succeeded()) {
                facilities = result.value();
                facility.setItems(facilities);
            } else {
                failure = result;
            }
        }
        if (specialties == null) {
            var result = api.specialties();
            if (result.succeeded()) {
                specialties = result.value();
                specialty.setItems(specialties);
            } else {
                failure = result;
            }
        }
        return failure;
    }

    private Result<List<SlotDto>> loadSlots(boolean showExchange) {
        var result = api.slots(SlotForm.id(facility), SlotForm.id(specialty));
        if (showExchange) {
            inspector.show(result.exchange());
        }
        grid.deselectAll();
        if (result.succeeded()) {
            grid.setItems(result.value());
            int size = result.value().size();
            status.setText(size + (size == 1 ? " slot" : " slots"));
        } else {
            grid.setItems(List.of());
            status.setText("Could not load appointment slots. " + result.error());
            notifyError(result.error());
        }
        return result;
    }

    private void filterChanged() {
        if (!clearing) {
            loadSlots(true);
        }
    }

    private void clearFilters() {
        clearing = true;
        facility.clear();
        specialty.clear();
        clearing = false;
        loadSlots(true);
    }

    private void openCreate() {
        var failure = loadReferences();
        if (failure != null) {
            reportFailure(failure);
            return;
        }
        new SlotForm(facilities, specialties, request -> finish(api.create(request), "Slot created.")).open();
    }

    private void openEdit(SlotDto slot) {
        var dialog = new Dialog();
        dialog.setHeaderTitle("Edit capacity for slot " + slot.id());
        var capacity = new IntegerField("Capacity");
        capacity.setValue(slot.capacity());
        dialog.add(capacity);
        var save = new Button("Save capacity", event -> {
            if (finish(api.updateCapacity(slot.id(), new UpdateCapacityRequest(capacity.getValue())),
                    "Capacity updated.")) {
                dialog.close();
            }
        });
        save.addThemeVariants(ButtonVariant.PRIMARY);
        dialog.getFooter().add(new Button("Cancel", event -> dialog.close()), save);
        dialog.open();
    }

    private void confirmDelete(SlotDto slot) {
        var dialog = new ConfirmDialog();
        dialog.setHeader("Delete slot " + slot.id() + "?");
        dialog.setText("This action permanently removes the appointment slot.");
        dialog.setCancelable(true);
        dialog.setConfirmText("Delete slot");
        dialog.setConfirmButtonTheme("danger primary");
        dialog.addConfirmListener(event -> finish(api.delete(slot.id()), "Slot deleted."));
        dialog.open();
    }

    private boolean finish(Result<?> result, String success) {
        if (!result.succeeded()) {
            reportFailure(result);
            return false;
        }
        inspector.show(result.exchange());
        var notification = Notification.show(success + " HTTP " + result.exchange().response().statusLine());
        notification.addThemeVariants(NotificationVariant.SUCCESS);
        loadSlots(false);
        return true;
    }

    private void reportFailure(Result<?> result) {
        inspector.show(result.exchange());
        notifyError(result.error());
    }

    private void notifyError(String error) {
        var notification = Notification.show(error, 5000, Notification.Position.TOP_CENTER);
        notification.addThemeVariants(NotificationVariant.ERROR);
    }
}
