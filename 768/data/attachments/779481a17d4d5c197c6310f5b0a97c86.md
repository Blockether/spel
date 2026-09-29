## DELETE http://localhost:40063/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 29 Sep 2026 15:44:56 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:40063/echo' \
  -X DELETE
```
