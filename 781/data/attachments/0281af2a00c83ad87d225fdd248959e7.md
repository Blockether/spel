## DELETE http://localhost:41147/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 05 Oct 2026 16:36:49 GMT
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
curl 'http://localhost:41147/echo' \
  -X DELETE
```
