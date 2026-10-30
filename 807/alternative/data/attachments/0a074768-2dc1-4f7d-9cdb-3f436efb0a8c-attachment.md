## DELETE http://localhost:34213/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 30 Oct 2026 12:21:28 GMT
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
curl 'http://localhost:34213/echo' \
  -X DELETE
```
