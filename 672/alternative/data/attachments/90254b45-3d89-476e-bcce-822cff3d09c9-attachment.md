## DELETE http://localhost:40439/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sat, 01 Aug 2026 08:57:30 GMT
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
curl 'http://localhost:40439/echo' \
  -X DELETE
```
