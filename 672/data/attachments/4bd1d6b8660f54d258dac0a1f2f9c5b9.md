## DELETE http://localhost:44191/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sat, 01 Aug 2026 08:57:34 GMT
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
curl 'http://localhost:44191/echo' \
  -X DELETE
```
