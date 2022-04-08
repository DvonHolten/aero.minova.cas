package aero.minova.cas.controller;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import aero.minova.cas.api.domain.Column;
import aero.minova.cas.api.domain.DataType;
import aero.minova.cas.api.domain.ProcedureException;
import aero.minova.cas.api.domain.Row;
import aero.minova.cas.api.domain.SqlProcedureResult;
import aero.minova.cas.api.domain.Table;
import aero.minova.cas.api.domain.Value;
import aero.minova.cas.api.domain.XProcedureException;
import aero.minova.cas.api.domain.XSqlProcedureResult;
import aero.minova.cas.api.domain.XTable;
import aero.minova.cas.CustomLogger;
import aero.minova.cas.service.SecurityService;
import aero.minova.cas.sql.SystemDatabase;

@RestController
public class XSqlProcedureController {

	@Autowired
	SystemDatabase systemDatabase;

	@Autowired
	CustomLogger customLogger;

	@Autowired
	SecurityService securityService;

	@Autowired
	SqlProcedureController sqlProcedureController;

	@Autowired
	SqlViewController sqlViewController;

	/**
	 * Führt eine Liste von voneinander abhängenden SQL-Prozeduren aus. Die Prozeduren müssen in der richtigen Reihenfolge gesendet werden. Erst wenn alle
	 * Prozeduren - d.h. die Anfrage und die dazugehörigen Checks - erfolgreich ausgeführt wurden, wird ein Commit getätigt. Tritt ein Fehler auf, werden alle
	 * bereits ausgeführten Prozeduren ge-rollbacked.
	 *
	 * @param inputTables
	 *            Liste der Tables mit Namen der Prozeduren und deren Aufruf Parameter
	 * @return Ergebnis Liste der Ergebnisse der Prozeduren-Aufrufe
	 * @throws Exception
	 *             Fehler bei der Ausführung von einer der Prozeduren. Rollback.
	 */
	@SuppressWarnings("unchecked")
	@PostMapping(value = "data/x-procedure")
	public ResponseEntity executeProcedures(@RequestBody List<XTable> inputTables) throws Exception {

		customLogger.logUserRequest("data/x-procedure: ", inputTables);
		List<XSqlProcedureResult> resultSets = new ArrayList<>();

		StringBuffer sb = new StringBuffer();
		Connection connection = null;

		try {
			connection = systemDatabase.getConnection();
			// Hier wird die Anfrage bearbeitet.
			resultSets = processXProcedures(inputTables, resultSets);

			// Hier werden die Checks nach der eigentlichen Anfrage ausgeführt.
			checkFollowUpProcedures(inputTables, resultSets);

			// Erst wenn auch die Checks erfolgreich waren, wird der Commit gesendet.
			connection.commit();
		} catch (Throwable e) {
			customLogger.logError("XSqlProcedure could not be executed: " + sb.toString(), e);
			try {
				connection.rollback();
				systemDatabase.freeUpConnection(connection);
			} catch (Exception e1) {
				customLogger.logError("Couldn't roll back xSqlProcedure execution", e);
				connection.close();
			}
			throw new XProcedureException(inputTables, resultSets, e);
		}
		return new ResponseEntity(resultSets, HttpStatus.ACCEPTED);
	}

	private List<XSqlProcedureResult> processXProcedures(List<XTable> inputTables, List<XSqlProcedureResult> resultSets)
			throws Exception, ProcedureException, SQLException {
		for (XTable xt : inputTables) {
			SqlProcedureResult result = new SqlProcedureResult();
			Table filledTable = new Table();
			// Referenzen auf Ergebnisse bereits ausgeführter Prozeduren auflösen.
			filledTable = fillInDependencies(xt, resultSets);

			// Rechteprüfung
			final List<Row> privilegeRequest = new ArrayList<>();
			if (securityService.arePrivilegeStoresSetup()) {
				privilegeRequest.addAll(securityService.getPrivilegePermissions(filledTable.getName()));
				if (privilegeRequest.isEmpty()) {
					throw new ProcedureException("msg.PrivilegeError %" + filledTable.getName());
				}
			}
			result = (SqlProcedureResult) sqlProcedureController.executeProcedure(filledTable).getBody();
			// SqlProcedureResult wird in Liste hinzugefügt, um dessen Werte später in andere Values schreiben zu können.
			resultSets.add(new XSqlProcedureResult(xt.getId(), result));
		}
		return resultSets;
	}

	/**
	 * Falls es einen Verweis auf ein OutputParameter einer anderen Prozedur gibt, wird der Value hier ersetzt.
	 * 
	 * @param dependant
	 *            Die Table, welche den potentiell-zu-ersetzenden Value enthält.
	 * @param dependencies
	 *            Die Liste an bereits ausgeführten xSqlProcedureResults. Diese beinhalten die Referenz-Values.
	 * @return Eine Table, in welcher der Value bereits ersetzt wurde.
	 */
	Table fillInDependencies(XTable dependant, List<XSqlProcedureResult> dependencies) {

		Table workingTable = dependant.getTable();

		for (Row r : workingTable.getRows()) {
			for (int i = 0; i < r.getValues().size(); i++) {
				Value v = r.getValues().get(i);
				// Die Referenz-Id steht in der Rule des Values. Im Value des Values steht, in welcher Column das der gewünschte Parameter steht.
				if (v != null && v.getRule() != null) {
					XSqlProcedureResult dependency = findxSqlResultSet(v.getRule(), dependencies);
					int position = 0;

					String stringValue = v.getStringValue();
					/*
					 * Bei mehreren Rows in einer Referenztabelle, wird mit - die Position angegeben, in der geschickten Table z.B. r-parent_call-0-KeyLong Der
					 * Value dazu würde hier folgendermaßen aussehen: Value("0-KeyLong","parent_call")
					 */
					if (stringValue.contains("-")) {
						// Spaltennamen könnten auch '-' enthalten, deshalb kein Split.
						String positionString = stringValue.substring(0, stringValue.indexOf("-"));
						position = Integer.valueOf(positionString);
						stringValue = stringValue.substring(positionString.length() + 1, stringValue.length());
					}

					// Ohne Outputparameter kann man nichts referenzieren.
					if (dependency.getResultSet().getOutputParameters() == null) {
						throw new RuntimeException("No output parameters for resultset with id " + dependency.getId());
					}

					Value newValue = findValueInColumn(dependency.getResultSet(), stringValue, position).orElse(null);
					if (newValue == null) {
						throw new RuntimeException("No reference value found for column" + stringValue + " in row " + position + " !");
					}
					// Tausche Value mit dem Ergebnis aus einem der ResultSets aus.
					r.getValues().remove(i);
					r.getValues().add(i, newValue);
				}
			}
		}

		return workingTable;
	}

	/**
	 * Findet den Value anhand des Spaltennamens.
	 * 
	 * @param dependency
	 *            Das SqlProcedureResult, welches den gewünschten Value enthält.
	 * @param columnName
	 *            Der Spaltenname der Spalte, welche den gesuchten Value enthält.
	 * @return Der Value aus der Spalte mit dem gesuchten Spaltennamen oder null, wenn die Spalte nicht gefunden werden kann.
	 */
	Optional<Value> findValueInColumn(SqlProcedureResult dependency, String columnName, int row) {
		for (int i = 0; i < dependency.getOutputParameters().getColumns().size(); i++) {
			if (dependency.getOutputParameters().getColumns().get(i).getName().equals(columnName)) {
				return Optional.ofNullable(dependency.getOutputParameters().getRows().get(row).getValues().get(i));
			}
		}
		return null;
	}

	/**
	 * Findet die gesuchte Referenz-Tabelle in den ResultSets.
	 * 
	 * @param idToFind
	 *            Die Id der Tabelle, welche zurückgegeben werden soll.
	 * @param resultsets
	 *            Die Liste an xSqlProcedureResults, welche zur Verfügung stehen.
	 * @return Das xSqlProcedureResult, welches die gewünschte Id hat.
	 */
	XSqlProcedureResult findxSqlResultSet(String idToFind, List<XSqlProcedureResult> resultsets) {
		for (XSqlProcedureResult xSqlResult : resultsets) {
			if (xSqlResult.getId().equals(idToFind)) {
				return xSqlResult;
			}
		}
		throw new RuntimeException("Cannot find SqlProcedureResult with Id " + idToFind);
	}

	/**
	 * Findet die gesuchte Referenz-Tabelle in den ResultSets.
	 * 
	 * @param tableName
	 *            Die Name der Tabelle, welche zurückgegeben werden soll.
	 * @param resultsets
	 *            Die Liste an xSqlProcedureResults, welche zur Verfügung stehen.
	 * @return Eine Liste an xSqlProcedureResults, welche den gewünschten Namen haben.
	 */
	List<XSqlProcedureResult> findxSqlResultSetByName(String tableName, List<XSqlProcedureResult> resultsets) {
		List<XSqlProcedureResult> resultsWithThatName = new ArrayList<>();
		for (XSqlProcedureResult xSqlResult : resultsets) {
			if (xSqlResult.getResultSet() != null && xSqlResult.getResultSet().getOutputParameters() != null
					&& xSqlResult.getResultSet().getOutputParameters().getName().equals(tableName)) {
				resultsWithThatName.add(xSqlResult);
			}
		}
		return resultsWithThatName;
	}

	/**
	 * Findet anhand der übergebenen Liste an XTables und über einen Aufruf der xtcasUserPrivilege-Tabelle heraus, welche Check-Prozeduren für die gerade
	 * ausgeführten XProzeduren durchgeführt werden müssen. In dieser Methode wird noch kein Commit an die Datenbank gesendet.
	 * 
	 * @param inputTables
	 *            Die Original-Anfrage, welche an das CAS gesendet wurde.
	 * @param xsqlresults
	 *            Die XSqlProcedureResults, welche die ausgeführten Prozeduren geliefert haben.
	 * @param connection
	 *            Die Verbindung zur Datenbank.
	 * @param sb
	 *            Ein StringBuffer, welcher das Ausführen der Check-Prozeudren loggt.
	 */
	private void checkFollowUpProcedures(List<XTable> inputTables, List<XSqlProcedureResult> xsqlresults) {
		// Die nötigen Check-Prozeduren aus der xtcasUserPrivilege-Tabelle auslesen.
		Table privilegeRequest = new Table();
		privilegeRequest.setName("xtcasUserPrivilege");
		privilegeRequest.addColumn(new Column("KeyText", DataType.STRING));
		privilegeRequest.addColumn(new Column("TransactionChecker", DataType.STRING));

		List<Row> inputRows = new ArrayList<>();
		for (XTable xTable : inputTables) {
			Row requestParam = new Row();
			privilegeRequest.getRows().add(requestParam);
			requestParam.addValue(new Value(xTable.getTable().getName(), null));
			requestParam.addValue(null);

			inputRows.add(requestParam);
		}
		try {
			Table checksPerPrivilege = securityService.getTableForSecurityCheck(privilegeRequest);

			// Wir müssen ja eigentlich einen Eintrag in der Datenbank dazu haben, sonst hätten wir sie bisher nicht ausführen können.
			if (checksPerPrivilege.getRows().size() == 0) {
				throw new RuntimeException("msg.PrivilegeError");
			}

			// Neue Prozedur-Aufrufe für alle gefundenen TransactionChecker zusammenbauen.
			List<XTable> checksXtables = new ArrayList<>();
			for (Row privilegeCheck : checksPerPrivilege.getRows()) {
				if (privilegeCheck.getValues().size() >= 2 && privilegeCheck.getValues().get(1) != null) {
					String dependencyTableName = privilegeCheck.getValues().get(0).getStringValue();
					String transactionChecker = privilegeCheck.getValues().get(1).getStringValue();

					// Alle ResultSets mit diesem Namen (nicht ID) müssen gecheckt werden.
					List<XSqlProcedureResult> resultsToCheck = findxSqlResultSetByName(dependencyTableName, xsqlresults);

					// Falls keine passenden OutputParameter gefunden werden können, muss das ResultSet des Haupt-Aufrufs (der erste in der Transaktion)
					// verwendet werden.
					if (resultsToCheck.size() == 0) {
						resultsToCheck.add(xsqlresults.get(0));
					}

					// Und von diesen muss jede Row geprüft werden. Dabei holen wir uns jedes mal den KeyLong (siehe Doku).
					for (XSqlProcedureResult res : resultsToCheck) {
						XTable followUpCheck = new XTable();
						followUpCheck.setId(dependencyTableName + transactionChecker);
						Table innerTable = new Table();
						innerTable.setName(transactionChecker);
						innerTable.addColumn(new Column("KeyLong", DataType.INTEGER));
						List<Row> checkArguments = new ArrayList<>();

						if (res.getResultSet().getOutputParameters() != null && res.getResultSet().getOutputParameters().getRows() != null) {
							for (int i = 0; i < res.getResultSet().getOutputParameters().getRows().size(); i++) {
								Value keyLongOfRow = findValueInColumn(res.getResultSet(), "KeyLong", i).orElse(null);

								// Falls die Prozedur bzw. dessen ResultSet keinen KeyLong als Output hatte, greifen wir auf den KeyLong des Haupt-ResultsSets
								// zurück.
								if (keyLongOfRow == null) {
									keyLongOfRow = findValueInColumn(xsqlresults.get(0).getResultSet(), "KeyLong", 0).orElse(null);
								}
								Row innerRow = new Row();
								innerRow.addValue(keyLongOfRow);
								checkArguments.add(innerRow);
								innerTable.setRows(checkArguments);
							}
						}
						// Es werden XTables verwendet, da die Prozeduren von den Ergebnissen anderer Prozeduren abhängen und so die bereits vorhandenen
						// Methoden wiederverwendet werden können.
						followUpCheck.setTable(innerTable);
						checksXtables.add(followUpCheck);
					}

				}
			}
			processXProcedures(checksXtables, xsqlresults);
		} catch (Exception e) {
			throw new RuntimeException("Error while trying to find follow up procedures.", e);
		}
	}
}