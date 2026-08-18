## DELETE http://localhost:44437/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 18 Aug 2026 14:32:04 GMT
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
curl 'http://localhost:44437/echo' \
  -X DELETE
```
