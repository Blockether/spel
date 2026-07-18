## DELETE http://localhost:36263/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sat, 18 Jul 2026 12:05:03 GMT
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
curl 'http://localhost:36263/echo' \
  -X DELETE
```
