## DELETE http://localhost:40449/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 02 Jun 2026 10:45:22 GMT
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
curl 'http://localhost:40449/echo' \
  -X DELETE
```
