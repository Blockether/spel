## DELETE http://localhost:45145/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 13 Apr 2026 12:07:00 GMT
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
curl 'http://localhost:45145/echo' \
  -X DELETE
```
